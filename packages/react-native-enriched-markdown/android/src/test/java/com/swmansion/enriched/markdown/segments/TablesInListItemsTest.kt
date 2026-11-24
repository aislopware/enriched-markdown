package com.swmansion.enriched.markdown.segments

import android.content.Context
import android.content.pm.ApplicationInfo
import android.text.Spannable
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.TextView
import com.facebook.react.bridge.JavaOnlyMap
import com.swmansion.enriched.markdown.parser.MarkdownASTNode
import com.swmansion.enriched.markdown.parser.MarkdownASTNode.NodeType
import com.swmansion.enriched.markdown.renderer.ListRenderer
import com.swmansion.enriched.markdown.spans.BaseListSpan
import com.swmansion.enriched.markdown.spans.ListItemSpacingSpan
import com.swmansion.enriched.markdown.spans.ListMarkerAnchorSpan
import com.swmansion.enriched.markdown.spans.MarginBottomSpan
import com.swmansion.enriched.markdown.spans.OrderedListSpan
import com.swmansion.enriched.markdown.spans.TaskListSpan
import com.swmansion.enriched.markdown.styles.StyleConfig
import com.swmansion.enriched.markdown.test.MarkdownRenderTestSupport.exportStyleMap
import com.swmansion.enriched.markdown.test.MarkdownRenderTestSupport.styleConfig
import com.swmansion.enriched.markdown.test.TestAstFactory.listItem
import com.swmansion.enriched.markdown.test.TestAstFactory.orderedList
import com.swmansion.enriched.markdown.test.TestAstFactory.paragraph
import com.swmansion.enriched.markdown.test.TestAstFactory.taskListItem
import com.swmansion.enriched.markdown.test.TestAstFactory.text
import com.swmansion.enriched.markdown.test.TestAstFactory.unorderedList
import com.swmansion.enriched.markdown.utils.common.layout.MarkdownWritingDirection
import com.swmansion.enriched.markdown.utils.text.conversion.HTMLGenerator
import com.swmansion.enriched.markdown.utils.text.conversion.MarkdownExtractor
import com.swmansion.enriched.markdown.utils.text.interaction.TaskListTapUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.ceil

/**
 * A table in a list item is lifted into a table segment of its own, between copies of the list
 * that keep its markers, numbers and nesting, and indented to the item's text column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TablesInListItemsTest {
  private val context get() = RuntimeEnvironment.getApplication()

  // List margins, so a gap the pieces of a split list add between them shows.
  private val style by lazy {
    styleConfig(list = mapOf("marginTop" to 8.0, "marginBottom" to 16.0), taskList = mapOf("checkedStrikethrough" to true))
  }

  @Test
  fun bulletItemSplitsAroundItsTable() {
    val segments = splitASTIntoSegments(document(unorderedList(item("a", table("t")), item("b"))))

    assertEquals(3, segments.size)
    val before = (segments[0] as MarkdownSegment.Text).nodes.single()
    assertEquals("0", before.children.single().getAttribute(ListRenderer.LIFT_KEY_ATTRIBUTE))
    assertEquals(listOf("a"), texts(before))
    assertEquals("true", before.getAttribute(ListRenderer.NO_MARGIN_BOTTOM_ATTRIBUTE))
    assertNull(before.getAttribute(ListRenderer.NO_MARGIN_TOP_ATTRIBUTE))

    val table = segments[1] as MarkdownSegment.Table
    assertEquals("t", table.node.content)
    assertEquals("0", table.liftKey)

    val after = (segments[2] as MarkdownSegment.Text).nodes.single()
    assertEquals(listOf("b"), texts(after))
    assertEquals("true", after.getAttribute(ListRenderer.NO_MARGIN_TOP_ATTRIBUTE))
    assertNull(after.getAttribute(ListRenderer.NO_MARGIN_BOTTOM_ATTRIBUTE))

    val rendered = render(document(unorderedList(item("a", table("t")), item("b"))))
    // Only the table's own margins separate it from the list.
    assertEquals(0f, (rendered[0] as RenderedSegment.Text).lastElementMarginBottom)
    assertInsetIsItemTextMargin(rendered, tableIndex = 1, headText = "a")
  }

  @Test
  fun orderedListKeepsItsNumbersFromItsStart() {
    val list = orderedList(item("x"), item("y", table("t")), item("z"), start = 3)
    val segments = splitASTIntoSegments(document(list))

    val before = (segments[0] as MarkdownSegment.Text).nodes.single()
    val after = (segments[2] as MarkdownSegment.Text).nodes.single()
    assertEquals("3", before.getAttribute("start"))
    assertEquals("5", after.getAttribute("start"))
    // Numbers under 100 keep the default marker column.
    assertNull(before.getAttribute(ListRenderer.MARKER_NUMBER_ATTRIBUTE))
    assertNull(after.getAttribute(ListRenderer.MARKER_NUMBER_ATTRIBUTE))

    val rendered = render(document(list))
    assertEquals(listOf(3, 4), markerNumbers(rendered[0]))
    assertEquals(listOf(5), markerNumbers(rendered[2]))
    assertInsetIsItemTextMargin(rendered, tableIndex = 1, headText = "y")
  }

  @Test
  fun everyPieceOfALongOrderedListFitsTheSameMarkerColumn() {
    fun markerColumns(vararg items: MarkdownASTNode): List<String?> =
      splitASTIntoSegments(document(orderedList(*items, start = 98)))
        .filterIsInstance<MarkdownSegment.Text>()
        .map { it.nodes.single().getAttribute(ListRenderer.MARKER_NUMBER_ATTRIBUTE) }

    val items = arrayOf(item("a"), item("b", table("t")), item("c"))
    assertEquals(listOf("999", "999"), markerColumns(*items))
    // A streamed item more keeps it, so the pieces before keep their signatures.
    assertEquals(listOf("999", "999"), markerColumns(*items, item("d")))
    // A task draws no number: here the largest drawn one is 99.
    assertEquals(listOf(null, null), markerColumns(item("a"), item("b", table("t")), taskListItem(checked = false, paragraph(text("c")))))
  }

  @Test
  fun textAfterTheTableContinuesTheItemWithoutAMarker() {
    val list = orderedList(item("a", table("t"), paragraph(text("after"))), item("b"))
    val segments = splitASTIntoSegments(document(list))

    assertEquals(3, segments.size)
    val (continuation, next) = (segments[2] as MarkdownSegment.Text).nodes
    val continuationItem = continuation.children.single()
    assertEquals("true", continuationItem.getAttribute(ListRenderer.CONTINUATION_ATTRIBUTE))
    assertEquals(listOf("after"), texts(continuation))
    assertEquals("true", continuation.getAttribute(ListRenderer.NO_MARGIN_TOP_ATTRIBUTE))
    assertEquals("2", next.getAttribute("start"))
    // Adjacent pieces of one list keep no list margins between them.
    assertEquals("true", continuation.getAttribute(ListRenderer.NO_MARGIN_BOTTOM_ATTRIBUTE))
    assertEquals("true", next.getAttribute(ListRenderer.NO_MARGIN_TOP_ATTRIBUTE))
    assertNull(next.getAttribute(ListRenderer.NO_MARGIN_BOTTOM_ATTRIBUTE))

    val rendered = render(document(list))
    assertEquals(listOf(2), markerNumbers(rendered[2]))
    val spanned = styledText(rendered[2])
    val afterSpan = deepestListSpanAt(spanned, "after")
    assertEquals(false, afterSpan.drawsMarker)
    assertTrue(afterSpan.continuesItem)
    // It carries the number of the item it continues.
    assertEquals(1, (afterSpan as OrderedListSpan).itemNumber)
    // No spacer line between the pieces: copied text has single newlines.
    assertEquals("after\nb", spanned.toString())
    assertEquals(textMarginAt(styledText(rendered[0]), "a"), textMarginAt(spanned, "after"))
    val margins =
      spanned
        .getSpans(spanned.indexOf("after"), spanned.indexOf("b"), MarginBottomSpan::class.java)
        .map { it.marginBottom }
    assertEquals(listOf(0f), margins)

    assertEquals("   after\n2. b", MarkdownExtractor.extractFromSpannable(spanned as Spannable, 0, spanned.length).trimEnd())
    // Copied as HTML, the piece's item keeps its number; the continuation takes none.
    val html = HTMLGenerator.generateHTML(spanned, StyleConfig(exportStyleMap(), context, false, 0f))
    assertEquals(listOf("2"), Regex("<li value=\"(\\d+)\"").findAll(html).map { it.groupValues[1] }.toList())
  }

  @Test
  fun aContinuationTakesNoItemGap() {
    val style = styleConfig(list = mapOf("itemSpacing" to 6.0))
    val continuation =
      MarkdownASTNode(
        NodeType.ListItem,
        attributes = mapOf(ListRenderer.CONTINUATION_ATTRIBUTE to "true"),
        children = listOf(paragraph(text("after"))),
      )
    val piece =
      MarkdownASTNode(
        NodeType.OrderedList,
        attributes = mapOf(ListRenderer.NO_MARGIN_TOP_ATTRIBUTE to "true"),
        children = listOf(continuation, item("b")),
      )
    val rendered = MarkdownSegmentRenderer.render(splitASTIntoSegments(document(paragraph(text("p")), piece)), style, context, null, null)

    val text = styledText(rendered.single())
    val gaps = text.getSpans(0, text.length, ListItemSpacingSpan::class.java).map { text.getSpanEnd(it) }
    // Only before "b", though the piece spaces its first item: "after" is more of the item before.
    assertEquals(listOf(text.indexOf("b")), gaps)
  }

  @Test
  fun anItemOpeningWithAPaddedQuoteDrawsItsMarkerOnTheQuotesText() {
    val padded = styleConfig(blockquote = mapOf("padding" to 12.0))
    val quote = MarkdownASTNode(NodeType.Blockquote, children = listOf(paragraph(text("Quoted"))))
    val segments = splitASTIntoSegments(document(unorderedList(listItem(quote, table("t")))))
    val text = styledText(MarkdownSegmentRenderer.render(segments, padded, context, null, null)[0])

    assertEquals('\n', text[0])
    val drawnOnTheirOwn = text.getSpans(0, text.length, BaseListSpan::class.java).filter { it.drawsMarker }
    assertTrue(drawnOnTheirOwn.isEmpty())
    val anchors = text.getSpans(0, text.length, ListMarkerAnchorSpan::class.java).map { text.getSpanStart(it) }
    assertEquals(listOf(text.indexOf("Quoted")), anchors)
  }

  @Test
  fun anEmptyCodeBlockOpeningAnItemLeavesNoMarginBeforeItsTable() {
    val spaced = styleConfig(codeBlock = mapOf("marginTop" to 16.0))
    val empty = MarkdownASTNode(NodeType.CodeBlock, children = emptyList())
    val segments = splitASTIntoSegments(document(unorderedList(item("Before"), listItem(empty, table("t")))))
    val head = MarkdownSegmentRenderer.render(segments, spaced, context, null, null)[0] as RenderedSegment.Text

    assertTrue(head.styledText!!.endsWith("Before\n "))
    assertEquals(0f, head.lastElementMarginBottom)
  }

  @Test
  fun aCodeBlockEndingAPieceKeepsItsOwnMargin() {
    val style = styleConfig(codeBlock = mapOf("marginBottom" to 12.0))
    val code = MarkdownASTNode(NodeType.CodeBlock, children = listOf(text("x\n")))
    val piece =
      MarkdownASTNode(
        NodeType.UnorderedList,
        attributes = mapOf(ListRenderer.NO_MARGIN_BOTTOM_ATTRIBUTE to "true"),
        children = listOf(listItem(paragraph(text("a")), code)),
      )
    val rendered = MarkdownSegmentRenderer.render(splitASTIntoSegments(document(piece, paragraph(text("p")))), style, context, null, null)

    val text = styledText(rendered.single())
    val margins = text.getSpans(0, text.length, MarginBottomSpan::class.java)
    val ranges = margins.map { text.getSpanStart(it) to text.getSpanEnd(it) }
    // One margin per newline: the code block's, without a zero list margin over it.
    assertEquals(ranges.distinct(), ranges)
    assertTrue(margins.any { it.marginBottom == 12f })
  }

  @Test
  fun aLaterPieceTakesTheItemGapBeforeItsFirstItem() {
    val style = styleConfig(list = mapOf("marginTop" to 8.0, "marginBottom" to 16.0, "itemSpacing" to 6.0))
    val list = orderedList(item("a", table("t"), paragraph(text("after"))), item("b"))
    val rendered = MarkdownSegmentRenderer.render(splitASTIntoSegments(document(list)), style, context, null, null)

    val text = styledText(rendered[2])
    val gaps = text.getSpans(0, text.length, ListItemSpacingSpan::class.java).map { text.getSpanStart(it) to text.getSpanEnd(it) }
    // Before "b", on the newline that ends "after"; none at the start of the segment, after the table.
    assertEquals(listOf(text.indexOf("b") - 1 to text.indexOf("b")), gaps)
  }

  @Test
  fun tableInANestedListLiftsOutOfEveryLevel() {
    val inner = unorderedList(item("inner", table("t"), paragraph(text("tail"))), item("inner2"))
    val outer = unorderedList(listItem(paragraph(text("outer")), inner), item("outer2"))
    val segments = splitASTIntoSegments(document(outer))

    assertEquals(3, segments.size)
    val beforeNodes = (segments[0] as MarkdownSegment.Text).nodes
    assertEquals(listOf(listOf("outer"), listOf("inner")), beforeNodes.map { texts(it) })
    // The inner head sits in a marker-less copy of its outer item.
    val wrappedItem = beforeNodes[1].children.single()
    assertEquals("true", wrappedItem.getAttribute(ListRenderer.CONTINUATION_ATTRIBUTE))
    assertEquals(NodeType.UnorderedList, wrappedItem.children.single().type)
    assertEquals("true", beforeNodes[0].getAttribute(ListRenderer.NO_MARGIN_BOTTOM_ATTRIBUTE))
    assertEquals("true", beforeNodes[1].getAttribute(ListRenderer.NO_MARGIN_TOP_ATTRIBUTE))
    assertEquals("true", beforeNodes[1].getAttribute(ListRenderer.NO_MARGIN_BOTTOM_ATTRIBUTE))

    val table = segments[1] as MarkdownSegment.Table
    assertEquals(
      wrappedItem.children
        .single()
        .children
        .single()
        .getAttribute(ListRenderer.LIFT_KEY_ATTRIBUTE),
      table.liftKey,
    )

    val afterNodes = (segments[2] as MarkdownSegment.Text).nodes
    assertEquals(listOf(listOf("tail"), listOf("inner2"), listOf("outer2")), afterNodes.map { texts(it) })

    val rendered = render(document(outer))
    val inset = (rendered[1] as RenderedSegment.Table).leadingInset
    assertTrue(inset > textMarginAt(styledText(rendered[0]), "outer"))
    assertInsetIsItemTextMargin(rendered, tableIndex = 1, headText = "inner")
    assertEquals(inset, textMarginAt(styledText(rendered[2]), "tail"))
  }

  @Test
  fun aTaskItemKeepsItsCheckboxAndTaskIndexOnItsHeadOnly() {
    val list =
      unorderedList(
        taskListItem(checked = true, paragraph(text("a")), table("t"), paragraph(text("after"))),
        taskListItem(checked = false, paragraph(text("b"))),
      )
    val segments = splitASTIntoSegments(document(list))

    val continuationItem = (segments[2] as MarkdownSegment.Text).nodes[0].children.single()
    assertEquals("true", continuationItem.getAttribute("isTask"))
    assertEquals("true", continuationItem.getAttribute(ListRenderer.CONTINUATION_ATTRIBUTE))
    assertNull(continuationItem.getAttribute(ListRenderer.LIFT_KEY_ATTRIBUTE))

    val rendered = render(document(list))
    assertEquals(listOf(0), taskIndices(rendered[0]))
    assertEquals(listOf(1), taskIndices(rendered[2]))
    // The text after the table keeps the checkbox column and the checked styling, but is no task.
    val after = styledText(rendered[2])
    val afterSpan = deepestListSpanAt(after, "after") as TaskListSpan
    assertEquals(-1, afterSpan.taskIndex)
    assertEquals(false, afterSpan.drawsMarker)
    assertEquals(textMarginAt(styledText(rendered[0]), "a"), textMarginAt(after, "after"))
    val at = after.toString().indexOf("after")
    assertTrue(after.getSpans(at, at + 1, StrikethroughSpan::class.java).isNotEmpty())
    assertInsetIsItemTextMargin(rendered, tableIndex = 1, headText = "a")

    // Copied, it continues the item under a list marker's width, not a checkbox's (an indented code block).
    val copied = MarkdownExtractor.extractFromSpannable(after as Spannable, 0, after.length)
    assertEquals("  after", copied.lines().first())

    val quote = MarkdownASTNode(NodeType.Blockquote, children = listOf(list))
    // In a quote too, the split list numbers two tasks and the tasks after the quote go on from there.
    val inQuote = MarkdownSegmentRenderer.render(splitASTIntoSegments(quote), style, context, null, null, style.blockquoteStyle)
    assertEquals(listOf(0), taskIndices(inQuote[0]))
    assertEquals(listOf(1), taskIndices(inQuote[2]))
    val afterQuote = render(document(quote, unorderedList(taskListItem(checked = false, paragraph(text("c"))))))
    assertTrue(afterQuote[0] is RenderedSegment.Blockquote)
    assertEquals(listOf(2), taskIndices(afterQuote[1]))
  }

  @Test
  fun aContinuationIsNoNewItemInCopiedHtml() {
    val list =
      unorderedList(
        taskListItem(checked = true, paragraph(text("a")), table("t"), paragraph(text("after1")), paragraph(text("after2"))),
        taskListItem(checked = false, paragraph(text("b"))),
      )
    val exportStyle = StyleConfig(exportStyleMap(), context, false, 0f)
    val html = HTMLGenerator.generateHTML(styledText(render(document(list))[2]) as Spannable, exportStyle)

    // One item for both paragraphs after the table, one for "b"; the only checkbox is b's, unchecked.
    assertEquals(2, Regex("<li").findAll(html).count())
    assertTrue(Regex("after1.*<br>.*after2").containsMatchIn(html))
    assertFalse(html.contains("&#10003;"))
    assertEquals(1, Regex("border: 1.5px solid").findAll(html).count())
  }

  @Test
  fun aTableFirstInItsItemStillShowsTheMarker() {
    val list = orderedList(item("a"), listItem(table("t"), paragraph(text("after"))))
    val segments = splitASTIntoSegments(document(list))

    assertEquals(3, segments.size)
    val head = (segments[0] as MarkdownSegment.Text).nodes.single().children[1]
    assertEquals(emptyList<MarkdownASTNode>(), head.children)

    val rendered = render(document(list))
    assertEquals(listOf(1, 2), markerNumbers(rendered[0]))
    assertEquals(emptyList<Int>(), markerNumbers(rendered[2]))
    // The marker's line: a space, so trimming the segment's trailing newlines keeps it.
    val text = styledText(rendered[0])
    assertTrue(text.endsWith("a\n "))
    assertTrue(deepestListSpanAt(text, " ").drawsOnBlankLine)
    assertInsetIsItemTextMargin(rendered, tableIndex = 1, headText = " ")
    assertNull((rendered[1] as RenderedSegment.Table).leadingInsetRtl)
  }

  @Test
  fun anItemOpeningWithANestedListThatOpensWithATableDrawsBothMarkersOnItsLine() {
    val list = orderedList(listItem(orderedList(listItem(table("t")))))
    val text = styledText(render(document(list))[0])

    // After the list's top margin spacer, one line holding a space.
    assertEquals("\n ", text.toString())
    val markerStarts =
      text.getSpans(0, text.length, OrderedListSpan::class.java).filter { it.drawsMarker }.map { text.getSpanStart(it) } +
        text.getSpans(0, text.length, ListMarkerAnchorSpan::class.java).map { text.getSpanStart(it) }
    assertEquals(listOf(1, 1), markerStarts)
  }

  @Test
  fun aNestedTaskOpeningWithItsTableKeepsItsOuterItemIndent() {
    val list = orderedList(listItem(paragraph(text("outer")), unorderedList(taskListItem(checked = false, table("t")))))
    val rendered = render(document(list))

    val head = styledText(rendered[0])
    val at = head.lastIndexOf(" ")
    // The outer item's continuation spans the nested head too, so the head has the table's indent.
    assertEquals(listOf(0, 1), head.getSpans(at, at + 1, BaseListSpan::class.java).map { it.depth }.sorted())
    assertInsetIsItemTextMargin(rendered, tableIndex = 1, headText = " ")

    val view = TextView(context).apply { text = head }
    view.measure(MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    view.layout(0, 0, 1000, view.measuredHeight)
    val line = view.layout.getLineForOffset(at)
    val inset = (rendered[1] as RenderedSegment.Table).leadingInset
    // Just before the text: the checkbox column, which ends at the full indent.
    assertEquals(0, TaskListTapUtils.hitTest(view, inset - 1f, view.layout.getLineTop(line) + 1f)?.taskIndex)
  }

  @Test
  fun aTaskContinuationInAnOrderedListCopiesUnderItsNumber() {
    val list = orderedList(taskListItem(checked = false, paragraph(text("a")), table("t"), paragraph(text("after"))), start = 10)
    val after = styledText(render(document(list))[2])

    // Indented past "10. ", as iOS copies it.
    assertEquals("    after", MarkdownExtractor.extractFromSpannable(after as Spannable, 0, after.length).lines().first())
  }

  @Test
  fun anItemOpeningWithANestedTableKeepsItsMarkerOnTheNestedLine() {
    val inner = unorderedList(item("inner", table("t")))
    val list = orderedList(item("first"), listItem(inner, paragraph(text("after"))), item("third"))
    val segments = splitASTIntoSegments(document(list))

    assertEquals(3, segments.size)
    val head = (segments[0] as MarkdownSegment.Text).nodes.single()
    assertEquals(listOf("first", "inner"), texts(head))
    val headItem = head.children[1]
    assertTrue(headItem.getAttribute(ListRenderer.LIFT_KEY_ATTRIBUTE) != null)
    assertNull(headItem.getAttribute(ListRenderer.CONTINUATION_ATTRIBUTE))
    assertEquals(NodeType.UnorderedList, headItem.children.single().type)
    val (continuation, third) = (segments[2] as MarkdownSegment.Text).nodes
    assertEquals(listOf("after"), texts(continuation))
    assertEquals("3", third.getAttribute("start"))

    val rendered = render(document(list))
    // No empty line for the outer item: its marker draws on the nested item's line.
    assertTrue(styledText(rendered[0]).contains("first\ninner"))
    assertEquals(listOf(1, 2), markerNumbers(rendered[0]))
    assertInsetIsItemTextMargin(rendered, tableIndex = 1, headText = "inner")
    assertEquals(2, (deepestListSpanAt(styledText(rendered[2]), "after") as OrderedListSpan).itemNumber)
    assertEquals(listOf(3), markerNumbers(rendered[2]))
  }

  @Test
  fun theInsetSideFollowsTheDirectionTheItemTextRendersIn() {
    fun side(
      head: String,
      direction: MarkdownWritingDirection = MarkdownWritingDirection.FIRST_STRONG,
    ): Boolean? {
      val style = styleConfig().apply { writingDirection = direction }
      val segments = splitASTIntoSegments(document(unorderedList(item(head, table("t")))))
      return (MarkdownSegmentRenderer.render(segments, style, context, null, null)[1] as RenderedSegment.Table).leadingInsetRtl
    }

    assertEquals(false, side("hello"))
    assertEquals(true, side("שלום עולם"))
    // First strong reads the first letter; word majority, as the text view resolves it then, most words.
    assertEquals(false, side("a שלום עולם"))
    assertEquals(true, side("a שלום עולם", MarkdownWritingDirection.WORD_MAJORITY))
    assertEquals(null, side("123"))

    // Past a code block's top-margin spacer: code reads left to right, whatever its letters.
    val codeStyle = styleConfig(codeBlock = mapOf("marginTop" to 8.0))
    val code = MarkdownASTNode(NodeType.CodeBlock, children = listOf(text("שלום עולם\n")))
    val codeFirst = splitASTIntoSegments(document(unorderedList(listItem(code, table("t")))))
    val codeTable = MarkdownSegmentRenderer.render(codeFirst, codeStyle, context, null, null)[1] as RenderedSegment.Table
    assertEquals(false, codeTable.leadingInsetRtl)
  }

  @Test
  fun aListInABlockquoteLiftsItsTableToo() {
    val quote = MarkdownASTNode(NodeType.Blockquote, children = listOf(unorderedList(item("a", table("t")))))
    val segments = splitASTIntoSegments(quote)

    assertEquals("0", (segments[1] as MarkdownSegment.Table).liftKey)
    val rendered = MarkdownSegmentRenderer.render(segments, style, context, null, null, style.blockquoteStyle)
    assertInsetIsItemTextMargin(rendered, tableIndex = 1, headText = "a")
  }

  @Test
  fun aTableOutsideAListItemIsNotLifted() {
    val segments = splitASTIntoSegments(document(unorderedList(item("a")), table("t")))
    val rendered = render(document(unorderedList(item("a")), table("t")))
    val lifted = render(document(unorderedList(item("a", table("t")))))

    assertNull((segments[1] as MarkdownSegment.Table).liftKey)
    assertEquals(0, (rendered[1] as RenderedSegment.Table).leadingInset)
    assertNotEquals(rendered[1].signature, lifted[1].signature)
  }

  @Test
  fun leadingInsetShiftsTheTableFromTheLeft() {
    assertEquals(TableBounds(40, 400, gridRight = 0f), tableBounds(leadingInset = 40, width = 400))
  }

  @Test
  @Config(qualifiers = "ar-ldrtl")
  fun leadingInsetShiftsTheTableFromTheRightInRtl() {
    // A narrow table hugs its leading edge: the right one.
    assertEquals(TableBounds(0, 360, gridRight = 360f), tableBounds(leadingInset = 40, width = 400))
  }

  @Test
  fun rightToLeftItemTextShiftsTheTableFromTheRightInALeftToRightLayout() {
    assertEquals(TableBounds(0, 360, gridRight = 360f), tableBounds(leadingInset = 40, width = 400, leadingInsetRtl = true))
  }

  @Test
  fun rowLinesSetTheOuterColumnsFlushWithTheTextAround() {
    val grid = laidOutTable(tableStyle("grid"))
    val rows = laidOutTable(tableStyle("rows"))
    val padding = TABLE_PADDING * context.resources.displayMetrics.density

    assertEquals(ceil(padding).toInt(), firstRowTexts(grid)[0].left)
    assertEquals(0, firstRowTexts(rows)[0].left)
    for (column in 0..1) {
      assertEquals(
        (grid.shownColumnWidths[column] - padding).toDouble(),
        rows.shownColumnWidths[column].toDouble(),
        0.01,
      )
    }
    val last = firstRowTexts(rows)[1]
    assertEquals(ceil(padding).toInt(), last.left)
    assertEquals((rows.shownColumnWidths[1] - padding).toInt(), last.width)
  }

  @Test
  @Config(qualifiers = "ar-ldrtl")
  fun rowLinesSetTheColumnsDrawnOutermostFlushInRtl() {
    val rows = laidOutTable(tableStyle("rows"))
    val padding = TABLE_PADDING * context.resources.displayMetrics.density
    val (first, last) = firstRowTexts(rows)

    // The first column is drawn rightmost: flush on its right.
    assertEquals(ceil(padding).toInt(), first.left)
    assertEquals((rows.shownColumnWidths[0] - padding).toInt(), first.width)
    assertEquals(0, last.left)
  }

  @Test
  fun rowLinesAddNoOuterBorderToTheHeight() {
    val gridStyle = tableStyle("grid")
    val rowsStyle = tableStyle("rows")
    val border = TABLE_BORDER.toFloat() * context.resources.displayMetrics.density
    val gridHeight = TableContainerView.measureTableNodeHeight(twoColumnTable(), gridStyle, context)
    val rowsHeight = TableContainerView.measureTableNodeHeight(twoColumnTable(), rowsStyle, context)

    assertEquals(gridHeight - border, rowsHeight, 0.01f)
    assertEquals(ceil(rowsHeight).toInt(), laidOutTable(rowsStyle).measuredHeight)
  }

  @Test
  fun aTableOverhangingInANestedContainerIsNotClippedByTheContainersAbove() {
    val table = TableContainerView(context, tableStyle("grid", horizontalOverflow = 8.0)).apply { applyTableNode(twoColumnTable()) }
    val quote = SegmentContainer(context, listOf(table))
    val root = SegmentContainer(context, listOf(quote))
    root.layout(0, 0, 800, 400)
    root.layOutSegments()
    quote.layOutSegments()

    assertFalse(root.clipChildren)
    assertFalse(root.clipToPadding)
    assertFalse(quote.clipToPadding)
  }

  private class SegmentContainer(
    context: Context,
    views: List<View>,
  ) : ContainerNodeView(context) {
    init {
      segmentViews.addAll(views)
      views.forEach { addView(it) }
    }

    fun layOutSegments() = layoutSegments()
  }

  private data class TableBounds(
    val left: Int,
    val right: Int,
    // The grid's right edge in the table's scroll view.
    val gridRight: Float,
  )

  private fun tableBounds(
    leadingInset: Int,
    width: Int,
    leadingInsetRtl: Boolean? = null,
  ): TableBounds {
    // The table view reads the table section, which only the export style map has.
    val view = TableContainerView(context, StyleConfig(exportStyleMap(), context, false, 0f))
    // The direction the markdown view it sits in would give it; views resolve RTL only in apps that support it.
    context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
    view.layoutDirection = context.resources.configuration.layoutDirection
    view.applyTableNode(table("t"))
    view.leadingInset = leadingInset
    view.leadingInsetRtl = leadingInsetRtl
    view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    view.layout(0, 0, width, view.measuredHeight)
    val table = view.getChildAt(0) as ViewGroup
    val grid = table.getChildAt(0)
    return TableBounds(table.left, table.right, grid.left + grid.translationX + grid.width)
  }

  private fun tableStyle(
    lines: String,
    horizontalOverflow: Double = 0.0,
  ): StyleConfig {
    val map = exportStyleMap()
    val table =
      JavaOnlyMap().apply {
        merge(map.getMap("table")!!)
        putString("lines", lines)
        putDouble("cellPaddingHorizontal", TABLE_PADDING)
        putDouble("borderWidth", TABLE_BORDER)
        putDouble("horizontalOverflow", horizontalOverflow)
      }
    map.putMap("table", table)
    return StyleConfig(map, context, false, 0f)
  }

  private fun laidOutTable(style: StyleConfig): TableContainerView {
    val view = TableContainerView(context, style)
    view.applyTableNode(twoColumnTable())
    view.measure(MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    view.layout(0, 0, 800, view.measuredHeight)
    return view
  }

  // The first row's cell texts, by column; the grid's other children are the rows' accessibility overlays.
  private fun firstRowTexts(view: TableContainerView): List<TextView> {
    val grid = (view.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
    val cells = (0 until grid.childCount).map { grid.getChildAt(it) }.filterIsInstance<ViewGroup>()
    return cells.take(2).map { it.getChildAt(0) as TextView }
  }

  private fun twoColumnTable(): MarkdownASTNode {
    fun row(
      cellType: NodeType,
      vararg cells: String,
    ) = MarkdownASTNode(NodeType.TableRow, children = cells.map { MarkdownASTNode(cellType, children = listOf(text(it))) })
    return MarkdownASTNode(
      NodeType.Table,
      children =
        listOf(
          MarkdownASTNode(NodeType.TableHead, children = listOf(row(NodeType.TableHeaderCell, "first", "last"))),
          MarkdownASTNode(NodeType.TableBody, children = listOf(row(NodeType.TableCell, "a", "b"))),
        ),
    )
  }

  private fun assertInsetIsItemTextMargin(
    rendered: List<RenderedSegment>,
    tableIndex: Int,
    headText: String,
  ) {
    val inset = (rendered[tableIndex] as RenderedSegment.Table).leadingInset
    assertTrue(inset > 0)
    assertEquals(textMarginAt(styledText(rendered[tableIndex - 1]), headText), inset)
  }

  private fun render(document: MarkdownASTNode): List<RenderedSegment> =
    MarkdownSegmentRenderer.render(splitASTIntoSegments(document), style, context, null, null)

  private fun styledText(segment: RenderedSegment): Spanned = (segment as RenderedSegment.Text).styledText!!

  private fun textMarginAt(
    text: Spanned,
    content: String,
  ): Int {
    val at = text.toString().lastIndexOf(content)
    return text.getSpans(at, at + 1, LeadingMarginSpan::class.java).sumOf { it.getLeadingMargin(true) }
  }

  private fun deepestListSpanAt(
    text: Spanned,
    content: String,
  ): BaseListSpan {
    val at = text.toString().indexOf(content)
    return text.getSpans(at, at + 1, BaseListSpan::class.java).maxBy { it.depth }
  }

  // The numbers drawn, by their own spans or by anchors on a nested item's line.
  private fun markerNumbers(segment: RenderedSegment): List<Int> {
    val text = styledText(segment)
    val own =
      text.getSpans(0, text.length, OrderedListSpan::class.java).filter { it.drawsMarker }.map { text.getSpanStart(it) to it }
    val anchored =
      text.getSpans(0, text.length, ListMarkerAnchorSpan::class.java).mapNotNull { anchor ->
        (anchor.marker as? OrderedListSpan)?.let { text.getSpanStart(anchor) to it }
      }
    return (own + anchored).sortedBy { it.first }.map { it.second.itemNumber }
  }

  private fun taskIndices(segment: RenderedSegment): List<Int> {
    val text = styledText(segment)
    return text
      .getSpans(0, text.length, TaskListSpan::class.java)
      .filter { it.drawsMarker }
      .sortedBy { text.getSpanStart(it) }
      .map { it.taskIndex }
  }

  // The text of each paragraph in [node], in order.
  private fun texts(node: MarkdownASTNode): List<String> =
    if (node.type == NodeType.Paragraph) {
      listOf(node.children.joinToString("") { it.content })
    } else {
      node.children.flatMap { texts(it) }
    }

  private fun item(
    content: String,
    vararg rest: MarkdownASTNode,
  ): MarkdownASTNode = listItem(paragraph(text(content)), *rest)

  private fun table(name: String): MarkdownASTNode = MarkdownASTNode(NodeType.Table, content = name)

  private fun document(vararg blocks: MarkdownASTNode): MarkdownASTNode = MarkdownASTNode(NodeType.Document, children = blocks.toList())

  private companion object {
    // Wider than the 60dp column minimum, so a flush side always narrows its column.
    const val TABLE_PADDING = 100.0
    const val TABLE_BORDER = 3.0
  }
}
