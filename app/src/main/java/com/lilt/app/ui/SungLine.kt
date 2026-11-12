package com.lilt.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.lilt.app.data.LyricLine

/**
 * The line being sung: a feathered wipe sweeps through each QRC word over its duration and sung words float up a touch.
 * The clock is only read while drawing, so per-frame updates never recompose or relayout, and wrapping stays natural.
 */
@Composable
fun SungLine(line: LyricLine, position: State<Long>, style: TextStyle, modifier: Modifier = Modifier) {
    val text = remember(line) { line.words.joinToString("") { it.text } }
    val starts = remember(line) { line.words.runningFold(0) { at, word -> at + word.text.length } }
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Text(text, style = style, onTextLayout = { layout[0] = it }, modifier = modifier
        .semantics { contentDescription = line.text }
        .drawWithContent {
            val result = layout[0] ?: return@drawWithContent drawContent()
            val now = position.value
            val current = line.words.indexOfLast { it.startMs <= now }
            val from = starts[current.coerceAtLeast(0)]
            val to = if (current < 0) 0 else starts[current + 1]
            val sung = if (current < 0) 0f else line.words[current].progress(now)
            val rise = 2.dp.toPx()
            val feather = (result.getLineBottom(0) - result.getLineTop(0)) * .45f // ≈ 0.6em at the lyric line height
            fun left(l: Int) = if (from <= result.getLineStart(l)) result.getLineLeft(l) else result.getHorizontalPosition(from, true)
            fun right(l: Int) = if (to >= result.getLineEnd(l)) result.getLineRight(l) else result.getHorizontalPosition(to, true)
            // The wipe travels along the word as if its wrapped pieces were laid end to end.
            val wordLines = if (to > from) result.getLineForOffset(from)..result.getLineForOffset(to - 1) else IntRange.EMPTY
            var edge = -feather + sung * (wordLines.sumOf { (right(it) - left(it)).toDouble() }.toFloat() + feather)
            for (l in 0 until result.lineCount) {
                val top = if (l == 0) -Far else result.getLineTop(l)
                val bottom = if (l == result.lineCount - 1) Far else result.getLineTop(l + 1)
                fun cut(offset: Int) = when {
                    offset <= result.getLineStart(l) -> -Far
                    offset >= result.getLineEnd(l) -> Far
                    else -> result.getHorizontalPosition(offset, true)
                }
                val a = cut(from)
                val b = cut(to)
                drawSlice(result, -Far, top, a, bottom, rise, Lit)
                if (l in wordLines) {
                    val x = left(l) + edge
                    drawSlice(result, a, top, b, bottom, rise * FastOutSlowInEasing.transform(sung),
                        Brush.horizontalGradient(listOf(Color.White, UnsungInk), x, x + feather))
                    edge -= right(l) - left(l)
                }
                drawSlice(result, b, top, Far, bottom, 0f, Unsung)
            }
        })
}

/** Draws one horizontal slice of the text, raised by [lift] together with its clip so the same glyphs just move up. */
private fun DrawScope.drawSlice(result: TextLayoutResult, left: Float, top: Float, right: Float, bottom: Float, lift: Float, brush: Brush) {
    if (right > left) translate(top = -lift) { clipRect(left, top, right, bottom) { drawText(result, brush) } }
}

private const val Far = 1e5f
private val UnsungInk = Color.White.copy(alpha = .45f)
private val Unsung = SolidColor(UnsungInk)
private val Lit = SolidColor(Color.White)
