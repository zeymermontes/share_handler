package com.shoutsocial.share_handler

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Copies a shared URI into the app's share inbox ([ShareInbox]).
 *
 * Only `content://` URIs served by another app's provider are accepted, and
 * their content is always copied into the inbox; other schemes and this
 * app's own providers are ignored. The provider's display name is used as
 * metadata only (see [ShareInbox.targetFor]).
 */
object FileDirectory {
    private const val TAG = "ShareHandler"
    private const val INBOX_MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun inboxDir(context: Context): File = File(context.cacheDir, ShareInbox.DIR_NAME)

    /** Deletes inbox entries left over from shares older than a day. */
    fun pruneInbox(context: Context) {
        try {
            ShareInbox.prune(inboxDir(context), System.currentTimeMillis(), INBOX_MAX_AGE_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not prune share inbox", e)
        }
    }

    /** Whether [uri] may be read as a share: `content://` from another app's provider. */
    fun isAcceptedUri(context: Context, uri: Uri): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return false
        val authority = uri.authority
        if (authority.isNullOrEmpty()) return false
        val ownPackage = context.packageName
        // Authorities are a single string or a ';'-separated list.
        if (authority.split(';').any { it == ownPackage || it.startsWith("$ownPackage.") }) {
            return false
        }
        val provider = try {
            context.packageManager.resolveContentProvider(authority, 0)
        } catch (e: Exception) {
            null
        }
        if (provider != null && provider.packageName == ownPackage) return false
        return true
    }

    /**
     * Copies [uri] into a fresh inbox directory and returns the file, or null
     * when the URI is not accepted or cannot be read.
     */
    fun importToInbox(context: Context, uri: Uri, mimeType: String?): File? {
        if (!isAcceptedUri(context, uri)) {
            Log.w(TAG, "Rejected shared URI (scheme or authority not allowed)")
            return null
        }
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri)
        val mimeExtension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

        val inbox = inboxDir(context)
        val target = try {
            ShareInbox.targetFor(inbox, UUID.randomUUID().toString(), displayName, mimeExtension)
        } catch (e: Exception) {
            Log.w(TAG, "Rejected shared item destination", e)
            return null
        }
        val itemDir = target.parentFile ?: return null
        return try {
            if (!itemDir.mkdirs() && !itemDir.isDirectory) return null
            ShareInbox.requireInside(inbox, itemDir)
            val copied = resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) {
                itemDir.deleteRecursively()
                return null
            }
            ShareInbox.requireInside(inbox, target)
            target
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy shared item", e)
            itemDir.deleteRecursively()
            null
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) c.getString(index) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
