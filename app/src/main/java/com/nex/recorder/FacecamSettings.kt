package com.nex.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Lembar "Pengaturan Facecam" (dibuka dari tombol gerigi pada facecam).
 * Perubahan langsung terlihat di facecam.
 */
class FacecamSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AppTheme { FacecamSheet(onDone = { finish() }) }
        }
    }
}

@Composable
private fun FacecamSheet(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var shape by remember { mutableIntStateOf(CamPrefs.shape(ctx)) }
    var effect by remember { mutableIntStateOf(CamPrefs.effect(ctx)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x990E1527))
            .pointerInput(Unit) { detectTapGestures { onDone() } },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(Unit) { detectTapGestures { } } // ketukan di lembar tidak menutupnya
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Pengaturan Facecam",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDone) { Text("Selesai") }
            }
            Text("Bentuk", style = MaterialTheme.typography.titleMedium)
            PillRow(
                listOf("Segi Empat" to CAM_SQUARE, "Lingkaran" to CAM_CIRCLE),
                shape,
            ) {
                shape = it
                CamPrefs.setShape(ctx, it)
            }
            Text("Efek", style = MaterialTheme.typography.titleMedium)
            PillRow(
                listOf(
                    "Tidak ada" to CAM_FX_NONE, "Bright" to CAM_FX_BRIGHT,
                    "Warm" to CAM_FX_WARM, "Dark" to CAM_FX_DARK,
                ),
                effect,
            ) {
                effect = it
                CamPrefs.setEffect(ctx, it)
            }
        }
    }
}

@Composable
private fun PillRow(options: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            val isSelected = value == selected
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(shape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
