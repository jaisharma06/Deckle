package com.avyaan.deckle.feature.markup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.avyaan.deckle.data.markup.MarkupPainter
import com.avyaan.deckle.domain.markup.PageMarkup
import java.io.File

/**
 * A box exactly the size of the page fitted into the available space, so overlays drawn in it line up with
 * the image pixel for pixel.
 */
@Composable
fun FittedPage(widthPx: Int, heightPx: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = widthPx.toFloat() / heightPx.coerceAtLeast(1)
        val byHeight = maxWidth / maxHeight > ratio
        val w = if (byHeight) maxHeight * ratio else maxWidth
        val h = if (byHeight) maxHeight else maxWidth / ratio
        Box(Modifier.size(w, h), content = content)
    }
}

/** The page image with its markup drawn over it (viewer). Same painter as the export, so it matches. */
@Composable
fun MarkedPage(
    imagePath: String,
    widthPx: Int,
    heightPx: Int,
    markup: PageMarkup?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
) {
    FittedPage(widthPx, heightPx, modifier) {
        AsyncImage(File(imagePath), contentDescription, contentScale = ContentScale.FillBounds, modifier = imageModifier.fillMaxSize())
        if (markup != null && !markup.isEmpty) MarkupOverlay(markup, Modifier.fillMaxSize())
    }
}

@Composable
fun MarkupOverlay(markup: PageMarkup, modifier: Modifier = Modifier, skip: String? = null) {
    val painter = remember { MarkupPainter() }
    Canvas(modifier) {
        drawIntoCanvas { painter.draw(it.nativeCanvas, markup, size.width, size.height, skip) }
    }
}
