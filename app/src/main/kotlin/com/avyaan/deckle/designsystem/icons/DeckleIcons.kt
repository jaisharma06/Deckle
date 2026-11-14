package com.avyaan.deckle.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Deckle's icon set: one consistent outline style (24-unit grid, 2-unit round stroke), only the icons we use.
 * Hand-built instead of the extended Material icons artifact, which is several MB (APK budget, spec 05 §2.4).
 * Tinted by `Icon`, so the stroke colour here is irrelevant.
 */
object DeckleIcons {
    val Camera by lazy {
        outline("camera") {
            moveTo(3f, 8f); lineTo(3f, 18f); arcTo(2f, 2f, 0f, false, false, 5f, 20f); lineTo(19f, 20f)
            arcTo(2f, 2f, 0f, false, false, 21f, 18f); lineTo(21f, 8f); arcTo(2f, 2f, 0f, false, false, 19f, 6f)
            lineTo(16f, 6f); lineTo(14.5f, 4f); lineTo(9.5f, 4f); lineTo(8f, 6f); lineTo(5f, 6f)
            arcTo(2f, 2f, 0f, false, false, 3f, 8f); close()
            circle(12f, 13f, 3.5f)
        }
    }
    val Scan by lazy {
        outline("scan") {
            moveTo(4f, 8f); lineTo(4f, 4f); lineTo(8f, 4f)
            moveTo(16f, 4f); lineTo(20f, 4f); lineTo(20f, 8f)
            moveTo(20f, 16f); lineTo(20f, 20f); lineTo(16f, 20f)
            moveTo(8f, 20f); lineTo(4f, 20f); lineTo(4f, 16f)
            moveTo(7f, 12f); lineTo(17f, 12f)
        }
    }
    val Back by lazy { outline("back", autoMirror = true) { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) } }
    val Forward by lazy { outline("forward", autoMirror = true) { moveTo(5f, 12f); lineTo(19f, 12f); moveTo(13f, 6f); lineTo(19f, 12f); lineTo(13f, 18f) } }
    val Close by lazy { outline("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    val Check by lazy { outline("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }
    val Add by lazy { outline("add") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }
    val More by lazy {
        outline("more") { circle(12f, 5.5f, 0.6f); circle(12f, 12f, 0.6f); circle(12f, 18.5f, 0.6f) }
    }
    val Search by lazy { outline("search") { circle(10.5f, 10.5f, 6f); moveTo(15f, 15f); lineTo(20f, 20f) } }
    val Delete by lazy {
        outline("delete") {
            moveTo(4f, 7f); lineTo(20f, 7f); moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
            moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
            moveTo(10f, 11f); lineTo(10f, 16f); moveTo(14f, 11f); lineTo(14f, 16f)
        }
    }
    val Restore by lazy {
        outline("restore") {
            moveTo(4f, 12f); arcTo(8f, 8f, 0f, true, true, 6.3f, 17.7f)
            moveTo(4f, 7f); lineTo(4f, 12f); lineTo(9f, 12f)
        }
    }
    val Share by lazy {
        outline("share") {
            moveTo(12f, 15f); lineTo(12f, 3f); moveTo(7.5f, 7.5f); lineTo(12f, 3f); lineTo(16.5f, 7.5f)
            moveTo(5f, 12f); lineTo(5f, 19f); arcTo(2f, 2f, 0f, false, false, 7f, 21f); lineTo(17f, 21f)
            arcTo(2f, 2f, 0f, false, false, 19f, 19f); lineTo(19f, 12f)
        }
    }
    val Save by lazy {
        outline("save") {
            moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
            moveTo(5f, 19f); lineTo(19f, 19f)
        }
    }
    val Copy by lazy {
        outline("copy") {
            moveTo(9f, 9f); lineTo(19f, 9f); lineTo(19f, 20f); lineTo(9f, 20f); close()
            moveTo(15f, 9f); lineTo(15f, 5f); lineTo(5f, 5f); lineTo(5f, 16f); lineTo(9f, 16f)
        }
    }
    val Gallery by lazy {
        outline("gallery") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(4f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(15.5f, 12.5f); lineTo(20f, 17f)
            circle(15.5f, 8.5f, 1.2f)
        }
    }
    val Flash by lazy { outline("flash") { moveTo(13f, 3f); lineTo(6f, 13f); lineTo(11f, 13f); lineTo(10f, 21f); lineTo(18f, 10f); lineTo(13f, 10f); close() } }
    val FlashOff by lazy {
        outline("flash_off") {
            moveTo(13f, 3f); lineTo(10.5f, 6.6f); moveTo(8.5f, 9.5f); lineTo(6f, 13f); lineTo(11f, 13f); lineTo(10f, 21f)
            lineTo(14.5f, 14.8f); moveTo(16.3f, 12.3f); lineTo(18f, 10f); lineTo(13.6f, 10f)
            moveTo(4f, 4f); lineTo(20f, 20f)
        }
    }
    val FlashAuto by lazy {
        outline("flash_auto") {
            moveTo(10f, 3f); lineTo(4f, 12f); lineTo(8.5f, 12f); lineTo(7.5f, 20f); lineTo(14f, 10f); lineTo(10f, 10f); close()
            moveTo(15f, 20f); lineTo(17.5f, 13f); lineTo(20f, 20f); moveTo(16f, 17.5f); lineTo(19f, 17.5f)
        }
    }
    val RotateLeft by lazy {
        outline("rotate_left") {
            moveTo(5f, 9f); arcTo(8f, 8f, 0f, true, true, 4f, 13f)
            moveTo(5f, 4f); lineTo(5f, 9f); lineTo(10f, 9f)
        }
    }
    val RotateRight by lazy {
        outline("rotate_right") {
            moveTo(19f, 9f); arcTo(8f, 8f, 0f, true, false, 20f, 13f)
            moveTo(19f, 4f); lineTo(19f, 9f); lineTo(14f, 9f)
        }
    }
    val Crop by lazy {
        outline("crop") {
            moveTo(6f, 2f); lineTo(6f, 18f); lineTo(22f, 18f)
            moveTo(2f, 6f); lineTo(18f, 6f); lineTo(18f, 22f)
        }
    }
    val Tune by lazy {
        outline("tune") {
            moveTo(4f, 7f); lineTo(11f, 7f); moveTo(15f, 7f); lineTo(20f, 7f); circle(13f, 7f, 2f)
            moveTo(4f, 17f); lineTo(7f, 17f); moveTo(11f, 17f); lineTo(20f, 17f); circle(9f, 17f, 2f)
        }
    }
    val Filter by lazy {
        outline("filter") {
            circle(9f, 9f, 5f); circle(15f, 15f, 5f)
        }
    }
    val Text by lazy {
        outline("text") {
            moveTo(5f, 6f); lineTo(19f, 6f); moveTo(5f, 10f); lineTo(19f, 10f); moveTo(5f, 14f); lineTo(19f, 14f); moveTo(5f, 18f); lineTo(13f, 18f)
        }
    }
    val Sort by lazy { outline("sort") { moveTo(4f, 7f); lineTo(20f, 7f); moveTo(7f, 12f); lineTo(17f, 12f); moveTo(10f, 17f); lineTo(14f, 17f) } }
    val Lock by lazy {
        outline("lock") {
            moveTo(5f, 11f); lineTo(19f, 11f); lineTo(19f, 20f); lineTo(5f, 20f); close()
            moveTo(8f, 11f); lineTo(8f, 8f); arcTo(4f, 4f, 0f, false, true, 16f, 8f); lineTo(16f, 11f)
        }
    }
    val Eye by lazy {
        outline("eye") {
            moveTo(2f, 12f); quadTo(12f, 2f, 22f, 12f); quadTo(12f, 22f, 2f, 12f); close(); circle(12f, 12f, 3f)
        }
    }
    val EyeOff by lazy {
        outline("eye_off") {
            moveTo(2f, 12f); quadTo(12f, 2f, 22f, 12f); quadTo(12f, 22f, 2f, 12f); close(); moveTo(4f, 4f); lineTo(20f, 20f)
        }
    }
    val Settings by lazy {
        outline("settings") {
            circle(12f, 12f, 3f)
            moveTo(12f, 2.5f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 21.5f)
            moveTo(2.5f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(21.5f, 12f)
            moveTo(5.3f, 5.3f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(18.7f, 18.7f)
            moveTo(5.3f, 18.7f); lineTo(7f, 17f); moveTo(17f, 7f); lineTo(18.7f, 5.3f)
            circle(12f, 12f, 7f)
        }
    }
    val Shield by lazy {
        outline("shield") {
            moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); quadTo(19f, 18f, 12f, 21f); quadTo(5f, 18f, 5f, 11f); lineTo(5f, 6f); close()
            moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.5f, 9.8f)
        }
    }
    val Warning by lazy {
        outline("warning") {
            moveTo(12f, 3.5f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close(); moveTo(12f, 10f); lineTo(12f, 14f); circle(12f, 17f, 0.4f)
        }
    }
    val Clock by lazy { outline("clock") { circle(12f, 12f, 8.5f); moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f) } }
    val Pages by lazy {
        outline("pages") {
            moveTo(8f, 3f); lineTo(19f, 3f); lineTo(19f, 17f)
            moveTo(5f, 6f); lineTo(16f, 6f); lineTo(16f, 21f); lineTo(5f, 21f); close()
        }
    }
    val Edit by lazy { outline("edit") { moveTo(4f, 20f); lineTo(4f, 16f); lineTo(15f, 5f); lineTo(19f, 9f); lineTo(8f, 20f); close(); moveTo(13f, 7f); lineTo(17f, 11f) } }
    val Insert by lazy {
        outline("insert") {
            moveTo(5f, 4f); lineTo(14f, 4f); lineTo(14f, 20f); lineTo(5f, 20f); close(); moveTo(18f, 9f); lineTo(18f, 15f); moveTo(15f, 12f); lineTo(21f, 12f)
        }
    }
    val Folder by lazy {
        outline("folder") {
            moveTo(3f, 7f); arcTo(2f, 2f, 0f, false, true, 5f, 5f); lineTo(9.5f, 5f); lineTo(11.5f, 7f); lineTo(19f, 7f)
            arcTo(2f, 2f, 0f, false, true, 21f, 9f); lineTo(21f, 17f); arcTo(2f, 2f, 0f, false, true, 19f, 19f)
            lineTo(5f, 19f); arcTo(2f, 2f, 0f, false, true, 3f, 17f); close()
        }
    }
    val FolderAdd by lazy {
        outline("folder_add") {
            moveTo(3f, 7f); arcTo(2f, 2f, 0f, false, true, 5f, 5f); lineTo(9.5f, 5f); lineTo(11.5f, 7f); lineTo(19f, 7f)
            arcTo(2f, 2f, 0f, false, true, 21f, 9f); lineTo(21f, 17f); arcTo(2f, 2f, 0f, false, true, 19f, 19f)
            lineTo(5f, 19f); arcTo(2f, 2f, 0f, false, true, 3f, 17f); close()
            moveTo(12f, 10f); lineTo(12f, 16f); moveTo(9f, 13f); lineTo(15f, 13f)
        }
    }
    val FolderMove by lazy {
        outline("folder_move") {
            moveTo(3f, 7f); arcTo(2f, 2f, 0f, false, true, 5f, 5f); lineTo(9.5f, 5f); lineTo(11.5f, 7f); lineTo(19f, 7f)
            arcTo(2f, 2f, 0f, false, true, 21f, 9f); lineTo(21f, 17f); arcTo(2f, 2f, 0f, false, true, 19f, 19f)
            lineTo(5f, 19f); arcTo(2f, 2f, 0f, false, true, 3f, 17f); close()
            moveTo(8.5f, 13f); lineTo(15.5f, 13f); moveTo(12.5f, 10f); lineTo(15.5f, 13f); lineTo(12.5f, 16f)
        }
    }
    val ChevronRight by lazy { outline("chevron_right", autoMirror = true) { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) } }
    val GridLarge by lazy {
        outline("grid_large") {
            moveTo(4f, 4f); lineTo(11f, 4f); lineTo(11f, 11f); lineTo(4f, 11f); close()
            moveTo(13f, 4f); lineTo(20f, 4f); lineTo(20f, 11f); lineTo(13f, 11f); close()
            moveTo(4f, 13f); lineTo(11f, 13f); lineTo(11f, 20f); lineTo(4f, 20f); close()
            moveTo(13f, 13f); lineTo(20f, 13f); lineTo(20f, 20f); lineTo(13f, 20f); close()
        }
    }
    val Grid by lazy {
        outline("grid") {
            for (y in listOf(4f, 10.5f, 17f)) for (x in listOf(4f, 10.5f, 17f)) {
                moveTo(x, y); lineTo(x + 3f, y); lineTo(x + 3f, y + 3f); lineTo(x, y + 3f); close()
            }
        }
    }
    val GridSmall by lazy {
        outline("grid_small") {
            for (y in listOf(4f, 9.5f, 15f, 20f)) for (x in listOf(4f, 9.5f, 15f, 20f)) circle(x, y, 0.9f)
        }
    }
    val ListView by lazy {
        outline("list") {
            moveTo(4f, 5f); lineTo(8f, 5f); lineTo(8f, 9f); lineTo(4f, 9f); close(); moveTo(11f, 7f); lineTo(20f, 7f)
            moveTo(4f, 15f); lineTo(8f, 15f); lineTo(8f, 19f); lineTo(4f, 19f); close(); moveTo(11f, 17f); lineTo(20f, 17f)
        }
    }
    val Details by lazy {
        outline("details") {
            moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 10f); lineTo(20f, 10f); moveTo(4f, 14f); lineTo(20f, 14f); moveTo(4f, 18f); lineTo(20f, 18f)
            moveTo(14f, 4f); lineTo(14f, 20f)
        }
    }
    val Info by lazy { outline("info") { circle(12f, 12f, 8.5f); moveTo(12f, 11f); lineTo(12f, 16f); circle(12f, 8f, 0.4f) } }

    // ---- Phases 3 and 4 ----
    val Pin by lazy {
        outline("pin") {
            moveTo(9f, 4f); lineTo(15f, 4f); moveTo(10f, 4f); lineTo(10f, 10f); lineTo(7f, 14f); lineTo(17f, 14f)
            lineTo(14f, 10f); lineTo(14f, 4f); moveTo(12f, 14f); lineTo(12f, 20f)
        }
    }
    val Tag by lazy {
        outline("tag") {
            moveTo(3.5f, 12.5f); lineTo(3.5f, 4.5f); lineTo(11.5f, 4.5f); lineTo(20f, 13f); lineTo(12.5f, 20.5f); close()
            circle(8f, 9f, 1.2f)
        }
    }
    val Calendar by lazy {
        outline("calendar") {
            moveTo(4f, 7f); lineTo(20f, 7f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            moveTo(4f, 11f); lineTo(20f, 11f); moveTo(8f, 4f); lineTo(8f, 8f); moveTo(16f, 4f); lineTo(16f, 8f)
        }
    }
    val Pen by lazy {
        outline("pen") { moveTo(4f, 20f); lineTo(5f, 15f); lineTo(16f, 4f); lineTo(20f, 8f); lineTo(9f, 19f); close(); moveTo(14f, 6f); lineTo(18f, 10f) }
    }
    val Highlighter by lazy {
        outline("highlighter") {
            moveTo(9f, 15f); lineTo(5f, 19f); lineTo(9f, 19f); lineTo(11f, 17f)
            moveTo(8f, 14f); lineTo(16f, 4f); lineTo(20f, 8f); lineTo(10f, 16f); close()
            moveTo(13f, 21f); lineTo(20f, 21f)
        }
    }
    val TextTool by lazy { outline("text_tool") { moveTo(5f, 6f); lineTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 6f); moveTo(12f, 4f); lineTo(12f, 20f); moveTo(9f, 20f); lineTo(15f, 20f) } }
    val Signature by lazy {
        outline("signature") {
            moveTo(3f, 15f); curveTo(6f, 6f, 9f, 5f, 9f, 9f); curveTo(9f, 13f, 6f, 17f, 7f, 17f)
            curveTo(9f, 17f, 11f, 11f, 13f, 12f); curveTo(14.5f, 13f, 14f, 16f, 16f, 15f); lineTo(18f, 13.5f)
            moveTo(3f, 20f); lineTo(21f, 20f)
        }
    }
    val Redact by lazy {
        outline("redact") {
            moveTo(3f, 9f); lineTo(21f, 9f); lineTo(21f, 15f); lineTo(3f, 15f); close()
            moveTo(6f, 9f); lineTo(12f, 15f); moveTo(11f, 9f); lineTo(17f, 15f); moveTo(16f, 9f); lineTo(21f, 14f)
        }
    }
    val Select by lazy {
        outline("select") { moveTo(6f, 4f); lineTo(6f, 19f); lineTo(10f, 15f); lineTo(13f, 21f); lineTo(15f, 20f); lineTo(12f, 14f); lineTo(18f, 14f); close() }
    }
    val Undo by lazy { outline("undo", autoMirror = true) { moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f); moveTo(4f, 9f); lineTo(14f, 9f); arcTo(5.5f, 5.5f, 0f, false, true, 14f, 20f); lineTo(11f, 20f) } }
    val Redo by lazy { outline("redo", autoMirror = true) { moveTo(15f, 14f); lineTo(20f, 9f); lineTo(15f, 4f); moveTo(20f, 9f); lineTo(10f, 9f); arcTo(5.5f, 5.5f, 0f, false, false, 10f, 20f); lineTo(13f, 20f) } }
    val Watermark by lazy {
        outline("watermark") {
            moveTo(5f, 3f); lineTo(15f, 3f); lineTo(19f, 7f); lineTo(19f, 21f); lineTo(5f, 21f); close()
            moveTo(8f, 17f); lineTo(16f, 10f)
        }
    }

    private fun outline(name: String, autoMirror: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f, autoMirror = autoMirror)
            .path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
            .build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        close()
    }
}
