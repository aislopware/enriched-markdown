package com.swmansion.enriched.markdown.spans

import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineBackgroundGeometryTest {
  private val paint = TextPaint().apply { textSize = 16f }

  @Test
  fun mixedDirectionRangeLeavesTheTextBetweenItsPiecesUncovered() {
    // Right to left, "def" sits at the right end of "abc def" and "שלום" left of it, past "abc".
    val text = "עולם abc def שלום"
    val layout = layout(text, 10000, TextDirectionHeuristics.RTL)
    val extents = extents(layout, 0, text.indexOf("def"), text.length)
    assertEquals(4, extents.size)
    val abc = layout.getPrimaryHorizontal(text.indexOf("b"))
    assertTrue("${extents.toList()} covers $abc", extents[1] < abc && abc < extents[2])
  }

  @Test
  fun rangeEndingAtASoftWrapStopsAtItsText() {
    val text = "aaaa bbbb cccc"
    val layout = layout(text, paint.measureText("aaaa bbb").toInt(), TextDirectionHeuristics.LTR)
    assertEquals(5, layout.getLineEnd(0))
    val ending = extents(layout, 0, 0, 5)
    assertEquals(2, ending.size)
    assertTrue("${ending.toList()} reaches the edge", ending[1] <= paint.measureText("aaaa ") + 1)
    // A range that goes on to the next line still reaches the edge.
    val continuing = extents(layout, 0, 0, 7)
    assertEquals(layout.width.toFloat(), continuing[1])
  }

  @Test
  fun rightToLeftRangeEndingAtASoftWrapStopsAtItsText() {
    val text = "אאאא בבבב גגגג"
    val layout = layout(text, paint.measureText("אאאא בבב").toInt(), TextDirectionHeuristics.RTL)
    assertEquals(5, layout.getLineEnd(0))
    val ending = extents(layout, 0, 0, 5)
    assertEquals(2, ending.size)
    assertTrue("${ending.toList()} reaches the edge", ending[0] >= layout.width - paint.measureText("אאאא ") - 1)
    // A range that goes on to the next line still reaches the edge.
    val continuing = extents(layout, 0, 0, 7)
    assertEquals(0f, continuing[0])
  }

  private fun layout(
    text: String,
    width: Int,
    direction: TextDirectionHeuristic,
  ): Layout =
    StaticLayout.Builder
      .obtain(text, 0, text.length, paint, width)
      .setTextDirection(direction)
      .build()

  private fun extents(
    layout: Layout,
    line: Int,
    start: Int,
    end: Int,
  ): FloatArray {
    var extents = FloatArray(0)
    InlineBackgroundGeometry.drawWith(layout) {
      extents =
        InlineBackgroundGeometry.horizontalExtents(
          layout.text,
          line,
          layout.getLineStart(line),
          layout.getLineEnd(line),
          start,
          end,
          paint,
          0,
          layout.width,
        )
    }
    return extents
  }
}
