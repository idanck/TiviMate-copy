package com.idanck.shidur.ui.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * The modules of a QR code for [text], row by row; `true` is dark. Medium error correction: a
 * phone reads it off a television across a room, glare and all, at a size that still fits beside
 * the code (ADR-059).
 */
fun qrModules(text: String): List<BooleanArray> {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
    return List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }
}

/**
 * [text] as a QR code: black on white whatever the theme, since that is what every camera
 * expects, inside the quiet zone the standard asks for.
 */
@Composable
fun QrCode(text: String, description: String, size: Dp, modifier: Modifier = Modifier) {
    val modules = remember(text) { runCatching { qrModules(text) }.getOrNull() } ?: return
    val count = modules.size
    Canvas(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(size / (count + QUIET_ZONE * 2) * QUIET_ZONE)
            .semantics { contentDescription = description },
    ) {
        val cell = this.size.minDimension / count
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                // Each cell a hair larger than its pitch, so no seam shows between neighbours.
                if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Modules of white around the code, as ISO/IEC 18004 asks. */
private const val QUIET_ZONE = 4
