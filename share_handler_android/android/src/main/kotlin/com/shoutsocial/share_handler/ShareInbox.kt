package com.shoutsocial.share_handler

import java.io.File
import java.io.IOException

/**
 * Where shared content lands inside the app: `cacheDir/share_in/<uuid>/<name>`.
 *
 * The location does not depend on the share's URI, display name or MIME
 * type: the directory is always a fresh random UUID, the display name is
 * reduced to a plain file name that only travels as metadata (the last path
 * segment, so the receiving app can show it), and the final location is
 * checked with canonical paths before anything is written.
 *
 * Plain JVM code (no Android types) so it can be unit tested.
 */
object ShareInbox {
    const val DIR_NAME = "share_in"
    private const val MAX_NAME_LENGTH = 120
    private const val FALLBACK_NAME = "file"
    private val EXTENSION = Regex("^[a-z0-9]{1,10}$")
    private val FORBIDDEN_CHARS = Regex("[\\u0000-\\u001f\\u007f/\\\\:*?\"<>|]")

    /**
     * [raw] reduced to a bare file name, or null when nothing usable is left.
     * Path separators keep only the last segment; control and reserved
     * characters are dropped; leading dots go so the result is never `.`,
     * `..` or a hidden file.
     */
    fun sanitizeDisplayName(raw: String?): String? {
        if (raw == null) return null
        val lastSegment = raw.substringAfterLast('/').substringAfterLast('\\')
        var name = lastSegment.replace(FORBIDDEN_CHARS, "").trim().trimStart('.').trim()
        if (name.isEmpty()) return null
        if (name.length > MAX_NAME_LENGTH) {
            val ext = name.substringAfterLast('.', "")
            name = if (ext.isNotEmpty() && ext.length <= 10 && ext.length < name.length - 1) {
                name.substring(0, MAX_NAME_LENGTH - ext.length - 1) + "." + ext
            } else {
                name.substring(0, MAX_NAME_LENGTH)
            }
        }
        return name
    }

    /** [ext] lower-cased if it is a plain extension (letters and digits), else null. */
    fun safeExtension(ext: String?): String? {
        val value = ext?.lowercase()?.trim() ?: return null
        return if (EXTENSION.matches(value)) value else null
    }

    /**
     * File name for a share: the sanitized display name, or [FALLBACK_NAME];
     * the MIME extension is added when the name has none.
     */
    fun fileName(displayName: String?, mimeExtension: String?): String {
        val base = sanitizeDisplayName(displayName) ?: FALLBACK_NAME
        val ext = safeExtension(mimeExtension)
        val hasExt = safeExtension(base.substringAfterLast('.', "")) != null &&
            base.contains('.')
        return if (!hasExt && ext != null) "$base.$ext" else base
    }

    /**
     * Destination for one shared item under [inboxDir], inside the [id]
     * directory. Throws [SecurityException] if the canonical result is not
     * strictly inside [inboxDir].
     */
    @Throws(SecurityException::class, IOException::class)
    fun targetFor(inboxDir: File, id: String, displayName: String?, mimeExtension: String?): File {
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' }
        if (safeId.isEmpty() || safeId != id) throw SecurityException("Invalid share id")
        val target = File(File(inboxDir, safeId), fileName(displayName, mimeExtension))
        requireInside(inboxDir, target)
        return target
    }

    /** Throws [SecurityException] unless [file] resolves strictly inside [dir]. */
    @Throws(SecurityException::class, IOException::class)
    fun requireInside(dir: File, file: File) {
        if (!isInside(dir, file)) {
            throw SecurityException("Share target escapes the inbox")
        }
    }

    @Throws(IOException::class)
    fun isInside(dir: File, file: File): Boolean {
        val root = dir.canonicalPath + File.separator
        return file.canonicalPath.startsWith(root)
    }

    /**
     * Removes item directories older than [maxAgeMs] so shares that were never
     * sent do not pile up in the cache.
     */
    fun prune(inboxDir: File, nowMs: Long, maxAgeMs: Long) {
        val entries = inboxDir.listFiles() ?: return
        for (entry in entries) {
            if (nowMs - entry.lastModified() > maxAgeMs) entry.deleteRecursively()
        }
    }
}
