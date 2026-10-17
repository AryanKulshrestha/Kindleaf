package com.kindleaf.data.remote

import android.content.Context
import android.net.Uri
import com.kindleaf.data.SupabaseHelper
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.util.UUID

object ImageStorageRepository {

    const val BUCKET = "images"
    private const val MAX_BYTES = 5 * 1024 * 1024

    /**
     * Uploads an image from a content Uri to Supabase Storage and returns its public URL.
     * Admins may upload anywhere in the bucket; customers only under `reviews/{their user id}/`
     * (see [reviewFolder]). Paths are random, so uploads never overwrite existing files.
     */
    suspend fun uploadImage(
        context: Context,
        uri: Uri,
        folder: String,
    ): String {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val (extension, contentType) = mimeToType(mime)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not read the selected image")
        if (bytes.isEmpty()) error("Selected image is empty")
        if (bytes.size > MAX_BYTES) error("Image is larger than 5 MB. Please choose a smaller photo.")

        val path = "${folder.trim('/').ifBlank { "uploads" }}/${UUID.randomUUID()}.$extension"
        val bucket = SupabaseHelper.client.storage.from(BUCKET)
        bucket.upload(path, bytes) {
            upsert = false
            this.contentType = contentType
        }
        return bucket.publicUrl(path)
    }

    /** Folder customers are allowed to upload review photos into. */
    fun reviewFolder(userId: String): String = "reviews/$userId"

    private fun mimeToType(mime: String): Pair<String, ContentType> = when {
        mime.contains("png") -> "png" to ContentType.Image.PNG
        mime.contains("webp") -> "webp" to ContentType.Image.WEBP
        mime.contains("gif") -> "gif" to ContentType.Image.GIF
        else -> "jpg" to ContentType.Image.JPEG
    }
}
