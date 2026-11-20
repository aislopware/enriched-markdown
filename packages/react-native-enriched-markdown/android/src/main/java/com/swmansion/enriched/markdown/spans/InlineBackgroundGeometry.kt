package com.swmansion.enriched.markdown.spans

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import kotlin.math.ceil
import kotlin.math.floor
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
   * The horizontal extent of [rangeStart, rangeEnd) on line [lineNum], which spans [lineStart,
   * lineEnd) of [text], in drawBackground's coordinates: the left and right of each visually
   * separate piece, left to right. A range that runs on past the line reaches the line's edge on
   * that side.
   *
   * The drawing layout's selection geometry follows its paragraph direction, bidi runs and
   * alignment. Without one (text drawn outside a markdown text view), a separate first-strong
   * layout of the line stands in, which only holds for left-to-right lines.
   */
  fun horizontalExtents(
    text: CharSequence,
    lineNum: Int,
    lineStart: Int,
    lineEnd: Int,
    rangeStart: Int,
    rangeEnd: Int,
    paint: Paint,
    left: Int,
    right: Int,
  ): FloatArray {
    val layout = drawingLayout(text, lineNum, lineStart)
    if (layout != null) {
      selectionExtents(layout, lineNum, max(rangeStart, lineStart), min(rangeEnd, lineEnd), rangeEnd <= lineEnd)?.let { return it }
    }
    val leadingMargin = if (text is Spanned) leadingMarginAt(text, lineStart) else 0
    val startX =
      if (rangeStart >= lineStart) {
        horizontalOffset(text, lineStart, lineEnd, rangeStart, paint, leadingMargin) + left
      } else {
        left.toFloat() + leadingMargin
      }
    val endX =
      if (rangeEnd <= lineEnd) {
        horizontalOffset(text, lineStart, lineEnd, rangeEnd, paint, leadingMargin) + left
      } else {
        right.toFloat()
      }
    return floatArrayOf(min(startX, endX), max(startX, endX))
  }

  /** Whether line [lineNum] of [text] reads right to left in its drawing layout; false without one. */
  fun isRightToLeftLine(
    text: CharSequence,
    lineNum: Int,
    lineStart: Int,
  ): Boolean = drawingLayout(text, lineNum, lineStart)?.getParagraphDirection(lineNum) == Layout.DIR_RIGHT_TO_LEFT

  private fun drawingLayout(
    text: CharSequence,
    lineNum: Int,
    lineStart: Int,
  ): Layout? = drawingLayout.get()?.takeIf { it.text === text && lineNum < it.lineCount && it.getLineStart(lineNum) == lineStart }

  private fun selectionExtents(
    layout: Layout,
    lineNum: Int,
    start: Int,
    end: Int,
    endsOnLine: Boolean,
  ): FloatArray? {
    if (start >= end) return null
    val selection = Path()
    layout.getSelectionPath(start, end, selection)
    val top = layout.getLineTop(lineNum)
    val bottom = layout.getLineBottom(lineNum)
    // getSelectionPath pads a range ending at the line's end, soft wrap included, out to the view's
    // edge and onto the next line. Keep this line's band, and the padding only for a range that
    // goes on. Trailing whitespace lies past getLineLeft/Right, on the paragraph's end side.
    val clip =
      if (endsOnLine) {
        val trailing = layout.getLineWidth(lineNum) - layout.getLineMax(lineNum)
        val rtl = layout.getParagraphDirection(lineNum) == Layout.DIR_RIGHT_TO_LEFT
        Rect(
          floor(layout.getLineLeft(lineNum) - if (rtl) trailing else 0f).toInt(),
          top,
          ceil(layout.getLineRight(lineNum) + if (rtl) 0f else trailing).toInt(),
          bottom,
        )
      } else {
        Rect(-layout.width, top, 2 * layout.width, bottom)
      }
    val region = Region()
    if (!region.setPath(selection, Region(clip))) return null
    // Bidi runs of one range can sit apart with other text between them: one extent per piece.
    // The region lists its rectangles band by band, each band left to right.
    val pieces = ArrayList<Rect>()
    val iterator = RegionIterator(region)
    while (true) {
      val piece = Rect()
      if (!iterator.next(piece)) break
      pieces.add(piece)
    }
    if (pieces.isEmpty()) return null
    pieces.sortBy { it.left }
    val extents = ArrayList<Float>(pieces.size * 2)
    for (piece in pieces) {
      if (extents.isNotEmpty() && piece.left <= extents.last()) {
        extents[extents.size - 1] = max(extents.last(), piece.right.toFloat())
      } else {
        extents.add(piece.left.toFloat())
        extents.add(piece.right.toFloat())
      }
    }
    return extents.toFloatArray()
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
