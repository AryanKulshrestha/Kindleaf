package com.kindleaf.ui.components

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kindleaf.data.remote.ImageStorageRepository
import com.kindleaf.data.userMessage
import com.kindleaf.ui.theme.PrimaryGreen
import kotlinx.coroutines.launch

/**
 * Device image picker that uploads to Supabase Storage and exposes the public URL.
 * Use this anywhere the app previously asked for an "Image URL".
 */
@Composable
fun ImagePickerField(
    imageUrl: String,
    onImageUrlChange: (String) -> Unit,
    folder: String,
    modifier: Modifier = Modifier,
    label: String = "Image",
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            error = null
            runCatching {
                ImageStorageRepository.uploadImage(context, uri, folder)
            }.onSuccess { url ->
                onImageUrlChange(url)
            }.onFailure { e ->
                error = e.userMessage("Upload failed. Please try again.")
            }
            uploading = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 120.dp else 180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            when {
                uploading -> CircularProgressIndicator(color = PrimaryGreen)
                imageUrl.isNotBlank() -> {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = label,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = {
                            onImageUrlChange("")
                            error = null
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Remove image",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "No image selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !uploading
            ) {
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    if (imageUrl.isBlank()) "  Select from device" else "  Change image",
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            if (imageUrl.isNotBlank() && !uploading) {
                TextButton(onClick = { onImageUrlChange(""); error = null }) {
                    Text("Remove")
                }
            }
        }

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Multi-image picker (e.g. review photos). Uploads each picked image to [folder] and reports the
 * public URLs. Shows per-batch upload progress, lets the user remove photos, and caps at [maxImages].
 */
@Composable
fun MultiImagePickerField(
    imageUrls: List<String>,
    onImageUrlsChange: (List<String>) -> Unit,
    folder: String,
    modifier: Modifier = Modifier,
    label: String = "Photos",
    maxImages: Int = 5,
    enabled: Boolean = true,
    onUploadingChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploadTotal by remember { mutableIntStateOf(0) }
    var uploadDone by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val uploading = uploadTotal > 0
    val currentUrls by rememberUpdatedState(imageUrls)
    val currentOnChange by rememberUpdatedState(onImageUrlsChange)
    val currentOnUploading by rememberUpdatedState(onUploadingChange)
    val remaining = (maxImages - imageUrls.size).coerceAtLeast(0)

    fun upload(uris: List<Uri>) {
        val room = (maxImages - currentUrls.size).coerceAtLeast(0)
        val toUpload = uris.take(room)
        if (toUpload.isEmpty()) return
        scope.launch {
            error = if (uris.size > room) "Only $maxImages photos allowed; extra photos were skipped." else null
            uploadTotal = toUpload.size
            uploadDone = 0
            currentOnUploading(true)
            var accumulated = currentUrls
            for (uri in toUpload) {
                runCatching { ImageStorageRepository.uploadImage(context, uri, folder) }
                    .onSuccess { url ->
                        accumulated = (accumulated + url).take(maxImages)
                        currentOnChange(accumulated)
                    }
                    .onFailure { e -> error = e.userMessage("Upload failed. Please try again.") }
                uploadDone++
            }
            uploadTotal = 0
            currentOnUploading(false)
        }
    }

    val singlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) upload(listOf(uri)) }

    val multiPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxImages.coerceAtLeast(2))
    ) { uris -> if (uris.isNotEmpty()) upload(uris) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "$label (${imageUrls.size}/$maxImages)",
            style = MaterialTheme.typography.labelLarge
        )
        if (imageUrls.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                imageUrls.forEachIndexed { index, url ->
                    Box(modifier = Modifier.size(80.dp)) {
                        AsyncImage(
                            model = url,
                            contentDescription = "Photo ${index + 1}",
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        IconButton(
                            onClick = {
                                currentOnChange(currentUrls.filterIndexed { i, _ -> i != index })
                                error = null
                            },
                            enabled = enabled && !uploading,
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Remove photo ${index + 1}",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (remaining <= 1) singlePicker.launch(request) else multiPicker.launch(request)
            },
            enabled = enabled && !uploading && remaining > 0
        ) {
            if (uploading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PrimaryGreen)
            } else {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                when {
                    uploading -> "Uploading ${(uploadDone + 1).coerceAtMost(uploadTotal)} of $uploadTotal…"
                    remaining == 0 -> "Photo limit reached"
                    else -> "Add photos"
                },
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (uploading) {
            LinearProgressIndicator(
                progress = { if (uploadTotal == 0) 0f else uploadDone.toFloat() / uploadTotal },
                modifier = Modifier.fillMaxWidth(),
                color = PrimaryGreen,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
