package com.swmansion.enriched.markdown.segments

import com.swmansion.enriched.markdown.parser.MarkdownASTNode
import com.swmansion.enriched.markdown.renderer.ListRenderer

sealed interface MarkdownSegment {
  data class Text(
    val nodes: List<MarkdownASTNode>,
  ) : MarkdownSegment

  data class Table(
    val node: MarkdownASTNode,
    /** Set for a table lifted out of a list item: the item's [ListRenderer.LIFT_KEY_ATTRIBUTE]. */
    val liftKey: String? = null,
  ) : MarkdownSegment

  data class Math(
    val latex: String,
    val node: MarkdownASTNode,
  ) : MarkdownSegment

  data class CodeBlock(
    val node: MarkdownASTNode,
  ) : MarkdownSegment

  data class Blockquote(
    val node: MarkdownASTNode,
  ) : MarkdownSegment

  data class Video(
    val node: MarkdownASTNode,
  ) : MarkdownSegment

  /** A block a React slot child stands in for (an image or link paragraph, a video or a code block). */
  data class BlockSlot(
    val slotId: String,
    val kind: BlockSlotKind,
  ) : MarkdownSegment
}

const val BLOCK_SLOT_ID_ATTRIBUTE = "blockSlotId"
const val BLOCK_SLOT_KIND_ATTRIBUTE = "blockSlotKind"

fun splitASTIntoSegments(root: MarkdownASTNode): List<MarkdownSegment> {
  val segments = mutableListOf<MarkdownSegment>()
  val currentTextNodes = mutableListOf<MarkdownASTNode>()
  var liftCount = 0

  fun flushTextNodes() {
    if (currentTextNodes.isNotEmpty()) {
      segments.add(MarkdownSegment.Text(currentTextNodes.toList()))
      currentTextNodes.clear()
    }
  }

  for (child in root.children) {
    val slotId = child.getAttribute(BLOCK_SLOT_ID_ATTRIBUTE)
    if (slotId != null) {
      flushTextNodes()
      segments.add(MarkdownSegment.BlockSlot(slotId, BlockSlotKind.fromAttribute(child.getAttribute(BLOCK_SLOT_KIND_ATTRIBUTE))))
      continue
    }
    when (child.type) {
      MarkdownASTNode.NodeType.Table -> {
        flushTextNodes()
        segments.add(MarkdownSegment.Table(child))
      }

      MarkdownASTNode.NodeType.LatexMathDisplay -> {
        flushTextNodes()
        val latex =
          if (child.children.isNotEmpty()) {
            child.children.first().content
          } else {
            child.content
          }
        segments.add(MarkdownSegment.Math(latex, child))
      }

      MarkdownASTNode.NodeType.CodeBlock -> {
        flushTextNodes()
        segments.add(MarkdownSegment.CodeBlock(child))
      }

      MarkdownASTNode.NodeType.Blockquote,
      MarkdownASTNode.NodeType.Admonition,
      -> {
        flushTextNodes()
        segments.add(MarkdownSegment.Blockquote(child))
      }

      MarkdownASTNode.NodeType.Video -> {
        flushTextNodes()
        segments.add(MarkdownSegment.Video(child))
      }

      MarkdownASTNode.NodeType.OrderedList,
      MarkdownASTNode.NodeType.UnorderedList,
      -> {
        if (!child.containsLiftedTable()) {
          currentTextNodes.add(child)
        } else {
          for (piece in liftTables(child) { (liftCount++).toString() }) {
            when (piece) {
              is LiftedPiece.Text -> {
                currentTextNodes.add(piece.node)
              }

              is LiftedPiece.Table -> {
                flushTextNodes()
                segments.add(MarkdownSegment.Table(piece.node, piece.liftKey))
              }
            }
          }
        }
      }

      else -> {
        currentTextNodes.add(child)
      }
    }
  }
  flushTextNodes()
  return segments
}

internal sealed interface LiftedPiece {
  class Text(
    val node: MarkdownASTNode,
  ) : LiftedPiece

  class Table(
    val node: MarkdownASTNode,
    val liftKey: String,
  ) : LiftedPiece
}

private fun MarkdownASTNode.isList(): Boolean =
  type == MarkdownASTNode.NodeType.OrderedList || type == MarkdownASTNode.NodeType.UnorderedList

/** A list with a table directly in one of its items, or in a list nested in one, at any depth. */
private fun MarkdownASTNode.containsLiftedTable(): Boolean = isList() && children.any { it.hasLiftedTable() }

private fun MarkdownASTNode.hasLiftedTable(): Boolean = type == MarkdownASTNode.NodeType.ListItem && children.any { it.liftsOut() }

private fun MarkdownASTNode.liftsOut(): Boolean = type == MarkdownASTNode.NodeType.Table || containsLiftedTable()

/**
 * [list] as top-level pieces: synthetic lists for its text, and the tables of its items between
 * them. The text pieces render with the original markers, numbers, nesting and indents.
 */
internal fun liftTables(
  list: MarkdownASTNode,
  nextLiftKey: () -> String,
): List<LiftedPiece> {
  val pieces = mutableListOf<LiftedPiece>()
  liftTables(list, { it }, nextLiftKey, pieces)
  return pieces.mapIndexed { index, piece ->
    if (piece !is LiftedPiece.Text) return@mapIndexed piece
    // Only the list's first piece keeps its top margin and its last piece its bottom one.
    var attributes = piece.node.attributes
    if (index > 0) attributes = attributes + (ListRenderer.NO_MARGIN_TOP_ATTRIBUTE to "true")
    if (index < pieces.lastIndex) attributes = attributes + (ListRenderer.NO_MARGIN_BOTTOM_ATTRIBUTE to "true")
    if (attributes === piece.node.attributes) piece else LiftedPiece.Text(piece.node.copy(attributes = attributes))
  }
}

/**
 * The number the marker column of every piece of an ordered [list] fits: all nines, as many as its
 * largest number from 100 on has, so it holds while a stream adds items. Task items draw no number.
 */
private fun markerColumnNumber(
  list: MarkdownASTNode,
  firstNumber: Int,
): String? {
  var number = firstNumber.toLong()
  var largest = 0L
  for (item in list.children) {
    if (item.type != MarkdownASTNode.NodeType.ListItem) continue
    if (item.getAttribute("isTask") != "true") largest = number
    number++
  }
  // Capped so the marker number still parses as an Int.
  return if (largest >= 100) "9".repeat(largest.coerceAtMost(999_999_999L).toString().length) else null
}

// [wrap] turns a copy of [list] into a top-level node, inside copies of its ancestors.
private fun liftTables(
  list: MarkdownASTNode,
  wrap: (MarkdownASTNode) -> MarkdownASTNode,
  nextLiftKey: () -> String,
  pieces: MutableList<LiftedPiece>,
) {
  val ordered = list.type == MarkdownASTNode.NodeType.OrderedList
  val firstNumber = ListRenderer.startNumber(list)
  val markerNumber = if (ordered) markerColumnNumber(list, firstNumber) else null

  fun listCopy(
    items: List<MarkdownASTNode>,
    number: Int,
  ): MarkdownASTNode {
    var attributes = list.attributes
    if (ordered) attributes = attributes + ("start" to number.toString())
    if (markerNumber != null) attributes = attributes + (ListRenderer.MARKER_NUMBER_ATTRIBUTE to markerNumber)
    return list.copy(attributes = attributes, children = items)
  }

  val fragment = mutableListOf<MarkdownASTNode>()
  var fragmentNumber = firstNumber
  var number = firstNumber

  fun flushFragment() {
    if (fragment.isEmpty()) return
    pieces.add(LiftedPiece.Text(wrap(listCopy(fragment.toList(), fragmentNumber))))
    fragment.clear()
  }

  for (item in list.children) {
    if (fragment.isEmpty()) fragmentNumber = number
    if (!item.hasLiftedTable()) {
      fragment.add(item)
      if (item.type == MarkdownASTNode.NodeType.ListItem) number++
      continue
    }

    val split = item.children.indexOfFirst { it.liftsOut() }
    val liftKey = nextLiftKey()
    val headAttributes = item.attributes + (ListRenderer.LIFT_KEY_ATTRIBUTE to liftKey)
    // An item that opens with a nested list holding a table has its head in the list's first piece.
    var headPending = split == 0 && item.children[0].type != MarkdownASTNode.NodeType.Table
    if (!headPending) {
      // An item that opens with the table has no children: it renders as an empty line with its marker.
      fragment.add(item.copy(attributes = headAttributes, children = item.children.subList(0, split)))
      flushFragment()
    }

    val continuationAttributes = item.attributes - ListRenderer.LIFT_KEY_ATTRIBUTE + (ListRenderer.CONTINUATION_ATTRIBUTE to "true")
    val itemNumber = number

    fun continuationOf(children: List<MarkdownASTNode>): MarkdownASTNode =
      listCopy(listOf(item.copy(attributes = continuationAttributes, children = children)), itemNumber)

    fun wrapNested(piece: MarkdownASTNode): MarkdownASTNode {
      if (!headPending) return wrap(continuationOf(listOf(piece)))
      headPending = false
      fragment.add(item.copy(attributes = headAttributes, children = listOf(piece)))
      return wrap(listCopy(fragment.toList(), fragmentNumber)).also { fragment.clear() }
    }

    val rest = mutableListOf<MarkdownASTNode>()

    fun flushRest() {
      if (rest.isEmpty()) return
      pieces.add(LiftedPiece.Text(wrap(continuationOf(rest.toList()))))
      rest.clear()
    }

    for (child in item.children.subList(split, item.children.size)) {
      when {
        child.type == MarkdownASTNode.NodeType.Table -> {
          flushRest()
          pieces.add(LiftedPiece.Table(child, liftKey))
        }

        child.containsLiftedTable() -> {
          flushRest()
          liftTables(child, ::wrapNested, nextLiftKey, pieces)
        }

        else -> {
          rest.add(child)
        }
      }
    }
    flushRest()
    number++
  }
  flushFragment()
}
