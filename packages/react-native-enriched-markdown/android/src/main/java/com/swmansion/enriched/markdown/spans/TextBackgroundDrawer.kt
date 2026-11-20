package com.swmansion.enriched.markdown.spans

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.text.Layout
import android.text.Spanned
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The text's background pass, under its glyphs: blockquote fills first, then highlight and inline
 * code backgrounds on top. Positions come from the layout ([Layout.getPrimaryHorizontal]) and are
 * cached per line until its text changes ([invalidateFrom]), the layout is replaced or resized, or
 * an inline image or link pill changes width ([noteReflow]). One per text view; main thread only.
 */
internal class TextBackgroundDrawer {
  companion object {
    private val clip = Rect()
    private val NO_PIECES = FloatArray(0)

    // Bumped when an inline image or link pill changes width in place: what follows it on its
    // line moves while the line's text stays the same.
    private var reflows = 0

    fun noteReflow() {
      reflows++
    }
  }

  // The positions found on a line while it runs from [start] to [end], by offset from [start].
  private class LinePositions(
    val start: Int,
    val end: Int,
  ) {
    val xs = FloatArray(end - start).apply { fill(Float.NaN) }
    var textEnd = Float.NaN

    // Whether the line may hold right-to-left text, once looked for.
    var mayMixDirections: Boolean? = null

    // The pieces of the ranges looked at on a line that may; none for a range in one direction.
    var pieces: HashMap<Long, FloatArray>? = null
  }

  private val lines = HashMap<Int, LinePositions>()

  // Handed out by [extents] for a range in one piece.
  private val extent = FloatArray(2)
  private var cachedLayout: Layout? = null
  private var cachedWidth = -1
  private var cachedReflows = -1

  /** The text changed from [offset] on: positions on lines that reach it are found again. */
  fun invalidateFrom(offset: Int) {
    lines.values.removeAll { it.end >= offset }
  }

  /** Draws under [view]'s text, clipped as [TextView.onDraw] clips the text. */
  fun draw(
    canvas: Canvas,
    view: TextView,
  ) {
    val layout = view.layout ?: return
    val top = view.extendedPaddingTop
    val bottom = view.extendedPaddingBottom
    val maxScrollY = layout.height - (view.height - top - bottom)
    canvas.save()
    canvas.clipRect(
      view.compoundPaddingLeft + view.scrollX,
      if (view.scrollY == 0) 0 else top + view.scrollY,
      view.width - view.compoundPaddingRight + view.scrollX,
      view.height + view.scrollY - (if (view.scrollY == maxScrollY) 0 else bottom),
    )
    draw(canvas, layout, layout.text, view.totalPaddingLeft.toFloat(), view.totalPaddingTop.toFloat())
    canvas.restore()
  }

  /** Draws under [layout]'s text, which the view draws at ([left], [top]) of [canvas]. */
  fun draw(
    canvas: Canvas,
    layout: Layout?,
    text: CharSequence?,
    left: Float,
    top: Float,
  ) {
    if (layout == null || text !is Spanned || layout.lineCount == 0 || !canvas.getClipBounds(clip)) return
    if (layout !== cachedLayout || layout.width != cachedWidth || reflows != cachedReflows) {
      lines.clear()
      cachedLayout = layout
      cachedWidth = layout.width
      cachedReflows = reflows
    }
    val first = layout.getLineForVertical((clip.top - top).toInt().coerceAtLeast(0))
    val last = layout.getLineForVertical((clip.bottom - 1 - top).toInt().coerceAtLeast(0))
    val start = layout.getLineStart(first)
    val end = layout.getLineEnd(last)
    val quotes = text.getSpans(start, end, BlockquoteSpan::class.java)
    val highlights = text.getSpans(start, end, HighlightSpan::class.java)
    val code = text.getSpans(start, end, CodeBackgroundSpan::class.java)
    if (quotes.isEmpty() && highlights.isEmpty() && code.isEmpty()) return

    canvas.save()
    canvas.translate(left, top)
    // Called as the layout would call a LineBackgroundSpan: the line box across the full width.
    for (span in quotes) {
      forEachLine(layout, text, span, first, last) { line, lineStart, lineEnd, _, _ ->
        span.drawBackground(canvas, 0, layout.width, layout.getLineTop(line), layout.getLineTop(line + 1), text, lineStart, lineEnd)
      }
    }
    for (span in highlights) {
      forEachLine(layout, text, span, first, last) { line, lineStart, lineEnd, spanStart, spanEnd ->
        span.drawLine(canvas, this, layout, line, lineStart, lineEnd, spanStart, spanEnd)
      }
    }
    for (span in code) {
      forEachLine(layout, text, span, first, last) { line, lineStart, lineEnd, spanStart, spanEnd ->
        span.drawLine(canvas, this, layout, line, lineStart, lineEnd, spanStart, spanEnd, text)
      }
    }
    canvas.restore()
  }

  // The shown lines [span] is on, from [first] to [last].
  private inline fun forEachLine(
    layout: Layout,
    text: Spanned,
    span: Any,
    first: Int,
    last: Int,
    draw: (line: Int, lineStart: Int, lineEnd: Int, spanStart: Int, spanEnd: Int) -> Unit,
  ) {
    val spanStart = text.getSpanStart(span)
    val spanEnd = text.getSpanEnd(span)
    if (spanStart !in 0 until spanEnd) return
    val from = maxOf(first, layout.getLineForOffset(spanStart))
    val to = minOf(last, layout.getLineForOffset(spanEnd - 1))
    for (line in from..to) draw(line, layout.getLineStart(line), layout.getLineEnd(line), spanStart, spanEnd)
  }

  /**
   * The x of [offset] clamped to [line]: at or past [lineEnd], where the line's glyphs end (a
   * wrapped line's end offset belongs to the next line).
   */
  fun x(
    layout: Layout,
    line: Int,
    lineStart: Int,
    lineEnd: Int,
    offset: Int,
  ): Float {
    if (lineStart >= lineEnd) return layout.getPrimaryHorizontal(lineStart)
    val positions = positionsOn(line, lineStart, lineEnd)
    if (offset >= lineEnd) {
      if (positions.textEnd.isNaN()) positions.textEnd = textEnd(layout, line, x(layout, line, lineStart, lineEnd, lineStart))
      return positions.textEnd
    }
    val at = maxOf(offset, lineStart)
    val kept = positions.xs[at - lineStart]
    if (!kept.isNaN()) return kept
    return layout.getPrimaryHorizontal(at).also { positions.xs[at - lineStart] = it }
  }

  /**
   * The left and right of each visually separate piece of [start, end) on [line], left to right,
   * to read before the next call. A range in one direction is one piece between its ends. One
   * that mixes directions has a piece per run, with the line's other text between them.
   */
  fun extents(
    layout: Layout,
    line: Int,
    lineStart: Int,
    lineEnd: Int,
    start: Int,
    end: Int,
  ): FloatArray {
    val from = max(start, lineStart)
    val to = min(end, lineEnd)
    if (from < to) {
      val positions = positionsOn(line, lineStart, lineEnd)
      if (positions.mayMixDirections == null) positions.mayMixDirections = mayHoldRightToLeft(layout.text, lineStart, lineEnd)
      if (positions.mayMixDirections == true) {
        val pieces = positions.pieces ?: HashMap<Long, FloatArray>().also { positions.pieces = it }
        val range = (from.toLong() shl 32) or to.toLong()
        val found = pieces.getOrPut(range) { mixedExtents(layout, line, lineStart, lineEnd, from, to) }
        if (found.isNotEmpty()) return found
      }
    }
    val startX = x(layout, line, lineStart, lineEnd, start)
    val endX = x(layout, line, lineStart, lineEnd, end)
    extent[0] = min(startX, endX)
    extent[1] = max(startX, endX)
    return extent
  }

  // Only characters from Hebrew on (U+0590) are right-to-left or change the direction.
  private fun mayHoldRightToLeft(
    text: CharSequence,
    start: Int,
    end: Int,
  ): Boolean {
    for (i in start until end) if (text[i].code >= 0x590) return true
    return false
  }

  // The pieces of [from, to), within [line], when it mixes directions; none when it runs in one.
  private fun mixedExtents(
    layout: Layout,
    line: Int,
    lineStart: Int,
    lineEnd: Int,
    from: Int,
    to: Int,
  ): FloatArray {
    val rightToLeft = layout.isRtlCharAt(from)
    if ((from + 1 until to).none { layout.isRtlCharAt(it) != rightToLeft }) return NO_PIECES
    // The selection geometry follows the line's bidi runs. It pads a range that reaches the line's
    // end out to the layout's edge and onto the next line: keep this line's band, under its glyphs.
    val selection = Path()
    layout.getSelectionPath(from, to, selection)
    val lineLeft = x(layout, line, lineStart, lineEnd, lineStart)
    val lineRight = x(layout, line, lineStart, lineEnd, lineEnd)
    val left = floor(min(lineLeft, lineRight)).toInt()
    val right = ceil(max(lineLeft, lineRight)).toInt()
    val band = Rect(left, layout.getLineTop(line), right, layout.getLineBottom(line))
    val region = Region()
    if (!region.setPath(selection, Region(band))) return NO_PIECES
    val rects = ArrayList<Rect>()
    val iterator = RegionIterator(region)
    while (true) {
      val rect = Rect()
      if (!iterator.next(rect)) break
      rects.add(rect)
    }
    rects.sortBy { it.left }
    val extents = ArrayList<Float>(rects.size * 2)
    for (rect in rects) {
      if (extents.isNotEmpty() && rect.left <= extents.last()) {
        extents[extents.size - 1] = max(extents.last(), rect.right.toFloat())
      } else {
        extents.add(rect.left.toFloat())
        extents.add(rect.right.toFloat())
      }
    }
    return extents.toFloatArray()
  }

  // The line's glyphs run from [start] for its extent less the leading margin. getLineLeft and
  // getLineRight count the margin twice before Android 10.
  private fun textEnd(
    layout: Layout,
    line: Int,
    start: Float,
  ): Float {
    val rightToLeft = layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT
    val margin = if (rightToLeft) layout.width - layout.getParagraphRight(line) else layout.getParagraphLeft(line)
    val extent = layout.getLineMax(line) - margin
    return if (rightToLeft) start - extent else start + extent
  }

  private fun positionsOn(
    line: Int,
    lineStart: Int,
    lineEnd: Int,
  ): LinePositions {
    val kept = lines[line]
    if (kept != null && kept.start == lineStart && kept.end == lineEnd) return kept
    return LinePositions(lineStart, lineEnd).also { lines[line] = it }
  }
}
