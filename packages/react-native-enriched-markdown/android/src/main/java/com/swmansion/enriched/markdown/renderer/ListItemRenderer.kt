package com.swmansion.enriched.markdown.renderer

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import com.swmansion.enriched.markdown.parser.MarkdownASTNode
import com.swmansion.enriched.markdown.spans.BaseListSpan
import com.swmansion.enriched.markdown.spans.CodeBlockSpan
import com.swmansion.enriched.markdown.spans.ListMarkerAnchorSpan
import com.swmansion.enriched.markdown.spans.OrderedListSpan
import com.swmansion.enriched.markdown.spans.TaskListSpan
import com.swmansion.enriched.markdown.spans.UnorderedListSpan
import com.swmansion.enriched.markdown.utils.common.layout.paragraphIsRtl
import com.swmansion.enriched.markdown.utils.text.span.SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE
import com.swmansion.enriched.markdown.utils.text.span.SPAN_FLAGS_LIST_ITEM

/**
 * Where a table lifted out of a list item starts: the item's content indent, on the side its first
 * paragraph renders from ([isRtl] null when that falls to the layout direction).
 */
data class LiftIndent(
  val indent: Int,
  val isRtl: Boolean?,
)

class ListItemRenderer(
  private val config: RendererConfig,
) : NodeRenderer {
  override fun render(
    node: MarkdownASTNode,
    builder: SpannableStringBuilder,
    onLinkPress: ((String) -> Unit)?,
    onLinkLongPress: ((String) -> Unit)?,
    factory: RendererFactory,
  ) {
    val styleContext = factory.blockStyleContext
    val start = builder.length
    val listType = styleContext.listType ?: return

    // The rest of an item a lifted table split: indented and styled as the item, but without a marker,
    // a number or a task index of its own.
    val continuation = node.attributes[ListRenderer.CONTINUATION_ATTRIBUTE] == "true"
    val liftKey = node.attributes[ListRenderer.LIFT_KEY_ATTRIBUTE]
    val isTask = node.attributes["isTask"] == "true"
    val isChecked = isTask && node.attributes["taskChecked"] == "true"

    if (listType == BlockStyleContext.ListType.ORDERED && !continuation) {
      styleContext.incrementListItemNumber()
    }

    val taskIndex = if (isTask && !continuation) styleContext.taskItemCount++ else -1

    val depth = styleContext.listDepth - 1
    val listStyle = config.style.listStyle

    fun makeListSpan(drawsMarker: Boolean): BaseListSpan =
      if (isTask) {
        TaskListSpan(
          taskStyle = config.style.taskListStyle,
          listStyle = listStyle,
          depth = depth,
          context = factory.context,
          styleCache = factory.styleCache,
          taskIndex = taskIndex,
          isChecked = isChecked,
          drawsMarker = drawsMarker,
          ancestorMarkerOffset = styleContext.ancestorMarkerOffset,
          markerExtraWidth = styleContext.listMarkerExtra,
          parentMarkerWidth = styleContext.listItemMarkerWidth,
        )
      } else {
        when (listType) {
          BlockStyleContext.ListType.UNORDERED -> {
            UnorderedListSpan(
              listStyle,
              depth,
              factory.context,
              factory.styleCache,
              drawsMarker,
              ancestorMarkerOffset = styleContext.ancestorMarkerOffset,
              parentMarkerWidth = styleContext.listItemMarkerWidth,
            )
          }

          BlockStyleContext.ListType.ORDERED -> {
            OrderedListSpan(
              listStyle,
              depth,
              factory.context,
              factory.styleCache,
              drawsMarker,
              markerExtraWidth = styleContext.listMarkerExtra,
              ancestorMarkerOffset = styleContext.ancestorMarkerOffset,
              parentMarkerWidth = styleContext.listItemMarkerWidth,
            ).apply {
              // A continuation shows the number of the item it continues, without advancing the count.
              setItemNumber(styleContext.listItemNumber + if (continuation) 1 else 0)
            }
          }
        }
      }

    fun makeSpan(drawsMarker: Boolean): BaseListSpan =
      makeListSpan(drawsMarker && !continuation).also {
        it.continuesItem = continuation
        if (continuation && listType == BlockStyleContext.ListType.ORDERED) it.continuedNumber = styleContext.listItemNumber + 1
      }

    val markerSpan = makeSpan(drawsMarker = true)

    val previousMarkerExtra = styleContext.listItemMarkerExtra
    styleContext.listItemMarkerExtra = markerSpan.markerExtraWidth
    val previousMarkerWidth = styleContext.listItemMarkerWidth
    styleContext.listItemMarkerWidth = markerSpan.baselineMarkerWidth
    val prevIndent = styleContext.accumulatedIndent
    styleContext.accumulatedIndent = prevIndent + markerSpan.getLeadingMargin(false)
    if (liftKey != null) styleContext.liftIndents[liftKey] = LiftIndent(styleContext.accumulatedIndent, null)
    try {
      factory.renderChildren(node, builder, onLinkPress, onLinkLongPress)
    } finally {
      styleContext.accumulatedIndent = prevIndent
      styleContext.listItemMarkerExtra = previousMarkerExtra
      styleContext.listItemMarkerWidth = previousMarkerWidth
    }

    if (liftKey != null) {
      // An item that opened with its table keeps a line for its marker. A space, not a bare newline:
      // a segment's trailing newlines are trimmed, and a kept one would add an empty last line. Not
      // before a nested head's space, which would push the nested span off its line start.
      // An empty code block's spacer goes too, or its margin would sit between the marker and the table.
      if (builder.substring(start).none { it != '\n' }) {
        builder.delete(start, builder.length)
        builder.append(" ")
        markerSpan.drawsOnBlankLine = true
      }
      // The first paragraph, past a leading block's top-margin spacer. Code blocks always render left to right.
      var paragraphStart = start
      while (builder[paragraphStart] == '\n') paragraphStart++
      val paragraphEnd = builder.indexOf('\n', paragraphStart).let { if (it < 0) builder.length else it + 1 }
      val isRtl =
        if (builder.getSpans(paragraphStart, paragraphEnd, CodeBlockSpan::class.java).isNotEmpty()) {
          false
        } else {
          config.style.writingDirection.paragraphIsRtl(builder, paragraphStart, paragraphEnd)
        }
      styleContext.liftIndents[liftKey] = styleContext.liftIndents.getValue(liftKey).copy(isRtl = isRtl)
    } else if (builder.length == start || (builder.substring(start).isBlank() && !holdsBlankLineMarker(builder, start))) {
      // An outer item wrapping a nested empty head still takes that line, or the head loses this item's indent.
      return
    }

    // The rest of an item is no item to space from the one before.
    if (!continuation) styleContext.listItemStarts.add(start)

    if (builder.last() != '\n') {
      builder.append("\n")
    }

    val itemEnd = builder.length

    // Anchor the marker on the first content line so items that open with a code block or nested
    // sublist still get their marker. It draws only from a line's start, so spaces stay.
    var anchor = start
    while (anchor < itemEnd && builder[anchor] == '\n') anchor++
    if (anchor == itemEnd) anchor = start

    // A leading block's spacer line is no line for the marker: it anchors on the first content line, as on iOS.
    val plainAnchor =
      builder[start] != '\n' &&
        builder.getSpans(anchor, anchor + 1, CodeBlockSpan::class.java).isEmpty() &&
        builder.getSpans(anchor, anchor + 1, BaseListSpan::class.java).none { it.depth > depth }

    val codeBlocks = builder.getSpans(start, itemEnd, CodeBlockSpan::class.java)
    // Nested items render first, so a code block keeps the innermost item that holds it.
    for (block in codeBlocks) {
      if (block.listItem == null) block.listItem = markerSpan
    }

    val codeBlockRanges =
      codeBlocks
        .map { builder.getSpanStart(it) to builder.getSpanEnd(it) }
        .filter { it.first < it.second }
        .sortedBy { it.first }

    var pos = start
    var isFirstSegment = true
    for ((cbStart, cbEnd) in codeBlockRanges) {
      if (pos < cbStart) {
        builder.setSpan(
          if (isFirstSegment && plainAnchor) markerSpan else makeSpan(drawsMarker = false),
          pos,
          cbStart,
          SPAN_FLAGS_LIST_ITEM,
        )
        isFirstSegment = false
      }
      pos = maxOf(pos, cbEnd)
    }
    if (pos < itemEnd) {
      builder.setSpan(
        if (isFirstSegment && plainAnchor) markerSpan else makeSpan(drawsMarker = false),
        pos,
        itemEnd,
        SPAN_FLAGS_LIST_ITEM,
      )
    }

    if (!plainAnchor && !continuation) {
      builder.setSpan(ListMarkerAnchorSpan(markerSpan), anchor, anchor + 1, SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE)
    }

    if (isTask && isChecked) {
      applyCheckedDecorations(builder, start, itemEnd, depth)
    }
  }

  private fun holdsBlankLineMarker(
    builder: SpannableStringBuilder,
    start: Int,
  ): Boolean = builder.getSpans(start, builder.length, BaseListSpan::class.java).any { it.drawsOnBlankLine }

  private fun applyCheckedDecorations(
    builder: SpannableStringBuilder,
    itemStart: Int,
    itemEnd: Int,
    itemDepth: Int,
  ) {
    val taskStyle = config.style.taskListStyle
    val checkedTextColor = taskStyle.checkedTextColor
    val strikethrough = taskStyle.checkedStrikethrough

    if (checkedTextColor == 0 && !strikethrough) return

    val excludedRanges =
      (
        builder.getSpans(itemStart, itemEnd, BaseListSpan::class.java).filter { it.depth > itemDepth } +
          builder.getSpans(itemStart, itemEnd, CodeBlockSpan::class.java).toList()
      ).map { builder.getSpanStart(it) to builder.getSpanEnd(it) }
        .sortedBy { it.first }

    var currentPos = itemStart

    for ((start, end) in excludedRanges) {
      if (start > currentPos) {
        applySpans(builder, currentPos, start, checkedTextColor, strikethrough)
      }
      currentPos = maxOf(currentPos, end)
    }

    if (currentPos < itemEnd) {
      applySpans(builder, currentPos, itemEnd, checkedTextColor, strikethrough)
    }
  }

  private fun applySpans(
    builder: SpannableStringBuilder,
    start: Int,
    end: Int,
    color: Int,
    strikethrough: Boolean,
  ) {
    if (color != 0) {
      builder.setSpan(ForegroundColorSpan(color), start, end, SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE)
    }
    if (strikethrough) {
      builder.setSpan(StrikethroughSpan(), start, end, SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE)
    }
  }
}
