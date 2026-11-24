package com.nex.recorder

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class RecItem(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long,
)

/** Daftar hasil rekaman di Movies/NexRecorder (terbaru di atas). */
fun loadRecordings(ctx: Context): List<RecItem> {
    val out = ArrayList<RecItem>()
    val proj = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
    )
    val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("Movies/NexRecorder%")
    ctx.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { c ->
        val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        while (c.moveToNext()) {
            val id = c.getLong(iId)
            out.add(
                RecItem(
                    uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    name = c.getString(iName) ?: "video",
                    durationMs = c.getLong(iDur),
                    size = c.getLong(iSize),
                ),
            )
        }
    }
    return out
}

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss) else String.format(Locale.US, "%d:%02d", m, ss)
}

private fun fmtSize(b: Long): String {
    val mb = b / 1048576.0
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.1f MB", mb)
}

@Composable
fun MediaTab() {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<RecItem>?>(null) }
    val resumeTick by UiSignals.resumeTick.collectAsState()
    LaunchedEffect(resumeTick) {
        items = withContext(Dispatchers.IO) { loadRecordings(ctx) }
    }
    val list = items
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(24.dp))
        Text("Media", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Hasil rekaman. Ketuk untuk memutar dan menyunting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        when {
            list == null -> Text("Memuat...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text(
                "Belum ada hasil rekaman.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(list, key = { _, it -> it.uri.toString() }) { i, item ->
                    RecRow(item) {
                        val intent = Intent(ctx, EditorActivity::class.java)
                            .putStringArrayListExtra("uris", ArrayList(list.map { it.uri.toString() }))
                            .putStringArrayListExtra("names", ArrayList(list.map { it.name }))
                            .putExtra("index", i)
                        ctx.startActivity(intent)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun RecRow(item: RecItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
            } catch (e: Exception) {
                null
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 96.dp, height = 64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val b = thumb
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${fmtDuration(item.durationMs)}  \u2022  ${fmtSize(item.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
