package com.swmansion.enriched.markdown.spans

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import kotlin.math.max
import kotlin.math.min

internal object InlineBackgroundGeometry {
  private val drawingLayout = ThreadLocal<Layout?>()

  /** Runs a text view's [draw] with its [layout] readable by the inline backgrounds it draws. */
  fun drawWith(
    layout: Layout?,
    draw: () -> Unit,
  ) {
    val previous = drawingLayout.get()
    drawingLayout.set(layout)
    try {
      draw()
    } finally {
      drawingLayout.set(previous)
    }
  }

  /**
   * Sets [out]'s left and right to the horizontal extent of [rangeStart, rangeEnd) on line
   * [lineNum], which spans [lineStart, lineEnd) of [text], in drawBackground's coordinates. A range
   * that runs on past the line reaches the line's edge on that side.
   *
   * The drawing layout's selection geometry follows its paragraph direction, bidi runs and
   * alignment. Without one (text drawn outside a markdown text view), a separate first-strong
   * layout of the line stands in, which only holds for left-to-right lines.
   */
  fun horizontalExtent(
    text: CharSequence,
    lineNum: Int,
    lineStart: Int,
    lineEnd: Int,
    rangeStart: Int,
    rangeEnd: Int,
    paint: Paint,
    left: Int,
    right: Int,
    out: RectF,
  ) {
    val layout = drawingLayout.get()?.takeIf { it.text === text && lineNum < it.lineCount && it.getLineStart(lineNum) == lineStart }
    if (layout != null && selectionExtent(layout, lineNum, max(rangeStart, lineStart), min(rangeEnd, lineEnd), out)) return
    val leadingMargin = if (text is Spanned) leadingMarginAt(text, lineStart) else 0
    out.left =
      if (rangeStart >= lineStart) {
        horizontalOffset(text, lineStart, lineEnd, rangeStart, paint, leadingMargin) + left
      } else {
        left.toFloat() + leadingMargin
      }
    out.right =
      if (rangeEnd <= lineEnd) {
        horizontalOffset(text, lineStart, lineEnd, rangeEnd, paint, leadingMargin) + left
      } else {
        right.toFloat()
      }
  }

  // A selection ending at the line's end continues onto the next line in getSelectionPath, which
  // pads both lines to their edges: keep only this line's band.
  private fun selectionExtent(
    layout: Layout,
    lineNum: Int,
    start: Int,
    end: Int,
    out: RectF,
  ): Boolean {
    if (start >= end) return false
    val selection = Path()
    layout.getSelectionPath(start, end, selection)
    val band = Path()
    band.addRect(
      -layout.width.toFloat(),
      layout.getLineTop(lineNum).toFloat(),
      2f * layout.width,
      layout.getLineBottom(lineNum).toFloat(),
      Path.Direction.CW,
    )
    if (!selection.op(band, Path.Op.INTERSECT) || selection.isEmpty) return false
    val bounds = RectF()
    selection.computeBounds(bounds, true)
    out.left = bounds.left
    out.right = bounds.right
    return true
  }

  /**
   * Returns the x position of [index] relative to the line's left edge. The measuring layout keeps
   * the line's spans, so a LeadingMarginSpan is already folded into getPrimaryHorizontal — only the
   * early return, which builds no layout, adds [leadingMargin] itself.
   */
  private fun horizontalOffset(
    text: CharSequence,
    lineStart: Int,
    lineEnd: Int,
    index: Int,
    paint: Paint,
    leadingMargin: Int,
  ): Float {
    if (index <= lineStart) return leadingMargin.toFloat()
    val lineText = text.subSequence(lineStart, lineEnd)
    val textPaint = paint as? TextPaint ?: TextPaint(paint)
    val layout = StaticLayout.Builder.obtain(lineText, 0, lineText.length, textPaint, 10000).build()
    return layout.getPrimaryHorizontal(index - lineStart)
  }

  private fun leadingMarginAt(
    text: Spanned,
    lineStart: Int,
  ): Int {
    if (lineStart >= text.length) return 0
    val spans = text.getSpans(lineStart, lineStart + 1, LeadingMarginSpan::class.java)
    var margin = 0
    for (span in spans) {
      margin += span.getLeadingMargin(false)
    }
    return margin
  }
}
