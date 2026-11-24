package com.swmansion.enriched.markdown.renderer

import android.text.SpannableStringBuilder
import com.swmansion.enriched.markdown.parser.MarkdownASTNode
import com.swmansion.enriched.markdown.spans.ListItemSpacingSpan
import com.swmansion.enriched.markdown.spans.MarginBottomSpan
import com.swmansion.enriched.markdown.spans.OrderedListSpan
import com.swmansion.enriched.markdown.styles.ListStyle
import com.swmansion.enriched.markdown.utils.text.span.SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE
import com.swmansion.enriched.markdown.utils.text.span.SPAN_FLAGS_LINE_HEIGHT_PRIORITY
import com.swmansion.enriched.markdown.utils.text.span.applyLineHeightSkippingImages
import com.swmansion.enriched.markdown.utils.text.span.applyMarginTop

class ListRenderer(
  private val config: RendererConfig,
  private val isOrdered: Boolean,
) : NodeRenderer {
  override fun render(
    node: MarkdownASTNode,
    builder: SpannableStringBuilder,
    onLinkPress: ((String) -> Unit)?,
    onLinkLongPress: ((String) -> Unit)?,
    factory: RendererFactory,
  ) {
    val start = builder.length
    // The items after the first of a list already shown (TextRenderCache): no gap above them.
    val continuation = node.getAttribute(CONTINUATION_ATTRIBUTE) == "true"
    val noMarginTop = continuation || node.getAttribute(NO_MARGIN_TOP_ATTRIBUTE) == "true"
    val listStyle = config.style.listStyle
    val listType = if (isOrdered) BlockStyleContext.ListType.ORDERED else BlockStyleContext.ListType.UNORDERED

    val startNumber = if (isOrdered) startNumber(node) else 1

    val markerExtraWidth = if (isOrdered) markerExtraWidth(node, listStyle, startNumber, factory) else 0f
    val contextManager = ListContextManager(factory.blockStyleContext)
    val entryState = contextManager.enterList(listType, listStyle, startNumber, markerExtraWidth)

    // For top-level lists, insert marginTop spacer before rendering content
    val topLevel = entryState.previousDepth == 0
    if (topLevel && !noMarginTop) {
      applyMarginTop(builder, start, listStyle.marginTop)
    }

    // Track start index after the potential 1-character marginTop spacer
    val contentStart = if (topLevel && !noMarginTop && listStyle.marginTop > 0) start + 1 else start

    // Ensure nested lists start on a new line if the parent hasn't provided one
    if (entryState.previousDepth > 0 && builder.isNotEmpty() && builder.last() != '\n') {
      builder.append("\n")
    }

    val styleContext = factory.blockStyleContext
    val itemStarts = styleContext.topLevelListItemStarts
    val itemTaskStarts = styleContext.topLevelListItemTaskStarts
    if (topLevel) {
      itemStarts.clear()
      itemTaskStarts.clear()
    }
    try {
      for (child in node.children) {
        if (topLevel) {
          itemStarts += builder.length
          itemTaskStarts += styleContext.taskItemCount
        }
        factory.renderNode(child, builder, onLinkPress, onLinkLongPress)
      }
    } finally {
      contextManager.exitList(entryState)
    }

    if (builder.length > contentStart) {
      val noMarginBottom = node.getAttribute(NO_MARGIN_BOTTOM_ATTRIBUTE) == "true"
      // A later piece of a list split around a lifted table: the gap above its first item is an item gap.
      val spacesFirstItem = node.getAttribute(NO_MARGIN_TOP_ATTRIBUTE) == "true" && contentStart > 0
      applyListSpacing(
        builder,
        contentStart,
        entryState.previousDepth,
        listStyle,
        noMarginBottom,
        spacesFirstItem,
        factory.blockStyleContext,
        itemStarts,
      )
    }
  }

  /**
   * Lists under 100 keep the `99.` column, so only numbers from 100 on widen it. Task items draw a checkbox.
   * A list split around lifted tables widens every piece by its largest number ([MARKER_NUMBER_ATTRIBUTE]).
   */
  private fun markerExtraWidth(
    node: MarkdownASTNode,
    listStyle: ListStyle,
    startNumber: Int,
    factory: RendererFactory,
  ): Float {
    val wideNumbers = mutableListOf<Int>()
    var number = startNumber
    for (item in node.children) {
      if (item.type != MarkdownASTNode.NodeType.ListItem || item.getAttribute(CONTINUATION_ATTRIBUTE) == "true") continue
      if (number >= 100 && item.attributes["isTask"] != "true") wideNumbers += number
      number++
    }
    node
      .getAttribute(MARKER_NUMBER_ATTRIBUTE)
      ?.toIntOrNull()
      ?.takeIf { it >= 100 }
      ?.let { wideNumbers += it }
    return OrderedListSpan.measureExtraWidth(listStyle, factory.context, wideNumbers)
  }

  private fun applyListSpacing(
    builder: SpannableStringBuilder,
    start: Int,
    depth: Int,
    style: ListStyle,
    noMarginBottom: Boolean,
    spacesFirstItem: Boolean,
    styleContext: BlockStyleContext,
    itemStarts: List<Int>,
  ) {
    // TODO: LineHeightSpan may also clip superscript/subscript glyphs that extend
    // outside the normal line bounds. A similar "skip" strategy as for images may
    // be needed here once super/subscript usage in practice is better understood.
    // Priority flag so the list's line height iterates before the children's
    // MarginBottomSpans in both the measuring StaticLayout and the TextView's copy
    // of the text (see SPAN_FLAGS_LINE_HEIGHT_PRIORITY). The span is added after the
    // children, so without the flag the two disagree on which runs last.
    //
    // A top-level list takes one per item, so the items of a list a stream grows render alike in
    // a render of the whole list and in one of its last items. A line never spans two items.
    if (depth == 0) {
      val end = builder.length
      itemStarts.forEachIndexed { i, from ->
        val to = itemStarts.getOrNull(i + 1) ?: end
        if (to > from) applyLineHeightSkippingImages(builder, from, to, style.lineHeight, SPAN_FLAGS_LINE_HEIGHT_PRIORITY)
      }
    } else {
      applyLineHeightSkippingImages(builder, start, builder.length, style.lineHeight, SPAN_FLAGS_LINE_HEIGHT_PRIORITY)
    }

    // Item spacing and external bottom margin are only handled by the root-level list
    if (depth == 0) {
      applyItemSpacing(builder, start, style.itemSpacing, styleContext, spacesFirstItem)

      if (noMarginBottom) {
        // No spacer line: the items already end in a newline. A zero margin on it still makes the
        // list, not an earlier block, the text's last margin (Renderer.removeTrailingMargin).
        // A code block or quote ending the list already put its own margin there.
        val last = builder.length - 1
        val hasMargin =
          builder.getSpans(last, builder.length, MarginBottomSpan::class.java).any {
            builder.getSpanStart(it) <= last && builder.getSpanEnd(it) >= builder.length
          }
        if (builder.last() == '\n' && !hasMargin) {
          builder.setSpan(MarginBottomSpan(0f), last, builder.length, SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE)
        }
      } else {
        builder.append("\n")
        builder.setSpan(
          MarginBottomSpan(style.marginBottom),
          builder.length - 1,
          builder.length,
          SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE,
        )
      }
    }
  }

  // Inserts the vertical gap between consecutive items (including nested ones)
  // by spanning the newline that precedes each item start. The first item of
  // the root list gets no spacing above it, unless [spacesFirstItem].
  private fun applyItemSpacing(
    builder: SpannableStringBuilder,
    start: Int,
    itemSpacing: Float,
    styleContext: BlockStyleContext,
    spacesFirstItem: Boolean,
  ) {
    if (itemSpacing <= 0f) return

    val itemStarts =
      styleContext.listItemStarts
        .filter { it in start until builder.length }
        .distinct()
        .sorted()

    for (itemStart in itemStarts) {
      if (itemStart == 0 || (itemStart == start && !spacesFirstItem)) continue
      builder.setSpan(
        ListItemSpacingSpan(itemSpacing),
        itemStart - 1,
        itemStart,
        SPAN_FLAGS_EXCLUSIVE_EXCLUSIVE,
      )
    }
  }

  companion object {
    /** On a list: the items after the first of a list already shown. On an item: the rest of an item split by a lifted table. */
    const val CONTINUATION_ATTRIBUTE = "enrmContinuation"

    // Set on the synthetic lists splitASTIntoSegments makes around tables lifted out of list items.
    const val LIFT_KEY_ATTRIBUTE = "enrmLiftKey"
    const val MARKER_NUMBER_ATTRIBUTE = "enrmMarkerNumber"
    const val NO_MARGIN_TOP_ATTRIBUTE = "enrmNoMarginTop"
    const val NO_MARGIN_BOTTOM_ATTRIBUTE = "enrmNoMarginBottom"

    /** The first number of an ordered list, as md4c reports it. */
    fun startNumber(list: MarkdownASTNode): Int = list.getAttribute("start")?.toIntOrNull()?.coerceAtLeast(0) ?: 1
  }
}
