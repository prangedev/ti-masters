package br.com.timasters.app.ui.screens.business

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.timasters.app.R
import br.com.timasters.app.data.business.OrderMediaRepository
import br.com.timasters.app.data.business.PhotoStage
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.OrderPhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// region Images

/** Decodes a local image off the main thread, shrunk to [maxSide] px; [version] forces a reload. */
@Composable
internal fun rememberLocalImage(path: String?, maxSide: Int, version: Any? = null): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, path, version, maxSide) {
        value = path?.let { withContext(Dispatchers.IO) { decodeScaled(it, maxSide)?.asImageBitmap() } }
    }
    return image
}

private fun decodeScaled(path: String, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

// endregion

// region Photos

/**
 * "Antes" and "Depois" rows with camera and gallery buttons. The camera writes to a temporary
 * file whose path survives the app being closed by Android while the camera is open.
 */
@Composable
internal fun OrderPhotosSection(
    photos: List<OrderPhotoItem>,
    newCameraFile: () -> File,
    cameraUri: (File) -> android.net.Uri,
    onAdd: (PhotoStage, android.net.Uri, File?) -> Unit,
    onDelete: (String) -> Unit,
    onCameraMissing: () -> Unit
) {
    /** "stage|path" of the photo being taken. */
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var galleryStage by rememberSaveable { mutableStateOf(PhotoStage.BEFORE) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val parts = pendingCapture?.split('|')
        pendingCapture = null
        if (parts == null || parts.size != 2) return@rememberLauncherForActivityResult
        val file = File(parts[1])
        if (success && file.length() > 0) onAdd(PhotoStage.from(parts[0]), android.net.Uri.fromFile(file), file) else file.delete()
    }
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(OrderMediaRepository.MAX_PHOTOS_PER_STAGE)
    ) { uris -> uris.forEach { onAdd(galleryStage, it, null) } }

    SectionCard(title = stringResource(id = R.string.photos_title)) {
        PhotoStage.entries.forEach { stage ->
            val ofStage = photos.filter { it.photo.photoStage == stage }
            Text(
                text = stringResource(id = R.string.photos_stage_count, stringResource(id = stage.labelResId), ofStage.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (ofStage.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    items(ofStage, key = { it.photo.id }) { item ->
                        PhotoThumbnail(item, onClick = { viewing = item.photo.id })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val file = newCameraFile()
                    pendingCapture = "${stage.serverId}|${file.path}"
                    try {
                        camera.launch(cameraUri(file))
                    } catch (_: ActivityNotFoundException) {
                        pendingCapture = null
                        onCameraMissing()
                    }
                }) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(id = R.string.photos_camera), modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = {
                    galleryStage = stage
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(id = R.string.photos_gallery), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        Text(
            text = stringResource(id = R.string.photos_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    viewing?.let { id ->
        val item = photos.firstOrNull { it.photo.id == id }
        if (item == null) {
            viewing = null
        } else {
            PhotoViewer(item = item, onDelete = { onDelete(id); viewing = null }, onDismiss = { viewing = null })
        }
    }
}

@Composable
private fun PhotoThumbnail(item: OrderPhotoItem, onClick: () -> Unit) {
    val image = rememberLocalImage(item.path, maxSide = 240)
    val description = stringResource(id = R.string.photos_open, stringResource(id = item.photo.photoStage.labelResId))
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = description, onClick = onClick)
    ) {
        if (image != null) {
            Image(bitmap = image, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // Taken on another phone and not downloaded yet (next sync).
            Icon(Icons.Default.CloudDownload, contentDescription = stringResource(id = R.string.photos_downloading))
        }
    }
}

@Composable
private fun PhotoViewer(item: OrderPhotoItem, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val image = rememberLocalImage(item.path, maxSide = 1600)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column {
                Box(contentAlignment = Alignment.Center, modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()) {
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = stringResource(id = item.photo.photoStage.labelResId),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(stringResource(id = R.string.photos_downloading), color = Color.White)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(id = R.string.photos_delete), color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = onDismiss) { Text(stringResource(id = R.string.close)) }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(id = R.string.photos_delete_title)) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(id = R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(id = R.string.cancel)) } }
        )
    }
}

// endregion

// region Signature

@Composable
internal fun OrderSignatureSection(
    signature: Pair<String, Long>?,
    signedBy: String?,
    signedAt: Long?,
    defaultSigner: String,
    onSave: (Bitmap, String) -> Unit,
    onRemove: () -> Unit
) {
    var drawing by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val image = rememberLocalImage(signature?.first, maxSide = 900, version = signature?.second)

    SectionCard(title = stringResource(id = R.string.signature_title)) {
        if (signedAt != null) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(id = R.string.signature_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                )
            }
            Text(
                text = stringResource(
                    id = R.string.signature_signed_by,
                    signedBy ?: stringResource(id = R.string.signature_unknown_signer),
                    BusinessTime.formatDateTime(signedAt)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { drawing = true }) { Text(stringResource(id = R.string.signature_again)) }
                TextButton(onClick = { confirmRemove = true }) { Text(stringResource(id = R.string.signature_remove)) }
            }
        } else {
            Text(
                text = stringResource(id = R.string.signature_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { drawing = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Draw, contentDescription = null)
                Text(stringResource(id = R.string.signature_collect), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (drawing) {
        SignaturePadDialog(
            defaultSigner = signedBy ?: defaultSigner,
            onDismiss = { drawing = false },
            onSave = { bitmap, signer ->
                drawing = false
                onSave(bitmap, signer)
            }
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(id = R.string.signature_remove_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(stringResource(id = R.string.signature_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(id = R.string.cancel)) } }
        )
    }
}

/** Full-screen pad: the client signs with a finger; the strokes become a PNG. */
@Composable
private fun SignaturePadDialog(defaultSigner: String, onDismiss: () -> Unit, onSave: (Bitmap, String) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var signer by rememberSaveable { mutableStateOf(defaultSigner) }
    val ink = Color(0xFF0D47A1)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(id = R.string.signature_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(id = R.string.signature_pad_hint), style = MaterialTheme.typography.bodyMedium)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.2f)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .onSizeChanged { padSize = it }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                current.clear()
                                current.add(down.position)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    current.add(change.position)
                                    change.consume()
                                }
                                strokes.add(current.toList())
                                current.clear()
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        (strokes + listOf(current.toList())).forEach { points ->
                            if (points.size == 1) {
                                drawCircle(ink, radius = 3.dp.toPx(), center = points.first())
                            } else if (points.size > 1) {
                                val path = Path().apply {
                                    moveTo(points.first().x, points.first().y)
                                    points.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(path, ink, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                    if (strokes.isEmpty() && current.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.signature_pad_placeholder),
                            color = Color.Gray,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                OutlinedTextField(
                    value = signer,
                    onValueChange = { signer = it.take(160) },
                    label = { Text(stringResource(id = R.string.signature_signer_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) {
                        Text(stringResource(id = R.string.signature_clear))
                    }
                    Box(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.cancel)) }
                    Button(
                        onClick = { onSave(renderSignature(strokes, padSize), signer) },
                        enabled = strokes.isNotEmpty() && padSize.width > 0
                    ) { Text(stringResource(id = R.string.save)) }
                }
            }
        }
    }
}

/** Draws the strokes into a transparent bitmap, shrunk to at most [MAX_SIGNATURE_WIDTH] px wide. */
private fun renderSignature(strokes: List<List<Offset>>, size: IntSize): Bitmap {
    val scale = minOf(1f, MAX_SIGNATURE_WIDTH.toFloat() / size.width)
    val width = (size.width * scale).toInt().coerceAtLeast(1)
    val height = (size.height * scale).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(0x0D, 0x47, 0xA1)
        style = Paint.Style.STROKE
        strokeWidth = maxOf(3f, 6f * scale)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { points ->
        if (points.size == 1) {
            canvas.drawCircle(points[0].x * scale, points[0].y * scale, paint.strokeWidth / 2, paint.apply { style = Paint.Style.FILL })
            paint.style = Paint.Style.STROKE
        } else {
            val path = android.graphics.Path().apply {
                moveTo(points[0].x * scale, points[0].y * scale)
                points.drop(1).forEach { lineTo(it.x * scale, it.y * scale) }
            }
            canvas.drawPath(path, paint)
        }
    }
    return bitmap
}

private const val MAX_SIGNATURE_WIDTH = 900

// endregion
