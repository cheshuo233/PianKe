package app.pianke.ui

import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pianke.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Photo-stack thumbnail of [lastPhoto] (tap -> [onGallery]), the shutter, and the lens flip. */
@Composable
fun BottomBar(lastPhoto: Uri?, onGallery: () -> Unit, onShutter: () -> Unit, onFlip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Photo(lastPhoto, onGallery)
        Shutter(onShutter)
        val flip = stringResource(R.string.flip_desc)
        Box(
            Modifier.size(52.dp).bezelButton(CircleShape).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onFlip).semantics { contentDescription = flip },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                scale(size.width / 24f, pivot = Offset.Zero) {
                    val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawArc(Ink, 180f, 138f, false, Offset(4f, 4f), androidx.compose.ui.geometry.Size(16f, 16f), style = stroke)
                    drawArc(Ink, 0f, 138f, false, Offset(4f, 4f), androidx.compose.ui.geometry.Size(16f, 16f), style = stroke)
                    drawPath(Path().apply { moveTo(20f, 3f); lineTo(20f, 7f); lineTo(16f, 7f) }, Ink, style = stroke)
                    drawPath(Path().apply { moveTo(4f, 21f); lineTo(4f, 17f); lineTo(8f, 17f) }, Ink, style = stroke)
                }
            }
        }
    }
}

@Composable
private fun Photo(uri: Uri?, onClick: () -> Unit) {
    val context = LocalContext.current
    val description = stringResource(R.string.gallery_desc)
    val thumbnail: ImageBitmap? by produceState<ImageBitmap?>(null, uri) {
        value = uri?.let {
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(it, Size(96, 88), null).asImageBitmap()
                } catch (_: java.io.IOException) {
                    null
                }
            }
        }
    }
    Box(
        Modifier.size(56.dp).rotate(-5f).shadow(4.dp, RoundedCornerShape(2.dp))
            .background(Color(0xFFFBF9F4)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
    ) {
        val image = thumbnail
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp, 44.dp))
        } else {
            Box(Modifier.size(48.dp, 44.dp).background(Color(0xFFC9B08C)))
        }
    }
}

@Composable
private fun Shutter(onClick: () -> Unit) {
    val description = stringResource(R.string.shutter_desc)
    Box(
        Modifier.size(72.dp).shadow(6.dp, CircleShape).clip(CircleShape)
            .background(Brush.sweepGradient(0f to Color(0xFFECECE9), 0.5f to Color(0xFF9A9A96), 1f to Color(0xFFECECE9)))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(
                Brush.radialGradient(
                    0f to Color.White, 0.35f to Color(0xFFE6E6E3), 0.75f to Color(0xFFBBBBB7), 1f to Color(0xFF9D9D99),
                ),
            ),
        )
    }
}
