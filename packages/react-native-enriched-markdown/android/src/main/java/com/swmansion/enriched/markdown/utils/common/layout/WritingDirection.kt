package com.swmansion.enriched.markdown.utils.common.layout

import android.os.Build
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.view.View
import com.swmansion.enriched.markdown.spans.CodeSpan
import java.nio.CharBuffer
import java.util.Locale

/** The `writingDirection` prop. Android resolves only these two; other values keep first-strong. */
enum class MarkdownWritingDirection {
  FIRST_STRONG,
  WORD_MAJORITY,
  ;

  /**
   * Whether layouts resolve paragraphs by word majority. Not below API 29: only from Q does
   * TextView take a subclass's heuristic, and the measuring layouts must resolve paragraphs the
   * way the view draws them.
   */
  val resolvesWordMajority: Boolean
    get() = this == WORD_MAJORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

  companion object {
    fun fromProp(value: String?): MarkdownWritingDirection = if (value == "word-majority") WORD_MAJORITY else FIRST_STRONG
  }
}

/**
 * Measures [text] with the heuristic a markdown text view draws it with. The neutral-paragraph
 * fallback is the locale's direction, which the view resolves too unless an ancestor sets `direction`.
 */
fun StaticLayout.Builder.setWritingDirection(
  direction: MarkdownWritingDirection,
  text: CharSequence,
): StaticLayout.Builder =
  apply {
    val textDirection = MarkdownTextDirection()
    textDirection.update(direction, text)
    textDirection.heuristic(TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL)?.let {
      setTextDirection(it)
    }
  }

/**
 * The heuristic a markdown text view resolves paragraphs with, kept in step with its text.
 * TextView caches its heuristic until its RTL properties change, so the objects handed out stay the
 * same while the text changes, and [update] says when the view must ask again.
 */
class MarkdownTextDirection {
  var writingDirection = MarkdownWritingDirection.FIRST_STRONG
    private set

  private val inlineCode = InlineCodeParagraphs()
  private val ltr: TextDirectionHeuristic = WordMajorityHeuristic(defaultIsRtl = false, inlineCode = inlineCode)
  private val rtl: TextDirectionHeuristic = WordMajorityHeuristic(defaultIsRtl = true, inlineCode = inlineCode)
  private var hasRightToLeft = false

  /** Reads [text] under [direction]. True when [heuristic] may now return another object. */
  fun update(
    direction: MarkdownWritingDirection,
    text: CharSequence?,
  ): Boolean {
    val directionChanged = direction != writingDirection
    writingDirection = direction
    if (!direction.resolvesWordMajority) {
      inlineCode.read(null)
      return directionChanged
    }
    val hadRightToLeft = hasRightToLeft
    hasRightToLeft = text != null && hasRightToLeftCharacter(text)
    inlineCode.read(text)
    return directionChanged || hadRightToLeft != hasRightToLeft
  }

  /**
   * Null keeps the platform's first-strong. Text that cannot hold a right-to-left paragraph in a
   * left-to-right layout gets FIRSTSTRONG_LTR, which resolves it the same way and is the only
   * kind of heuristic that lets StaticLayout skip bidi analysis.
   */
  fun heuristic(layoutRtl: Boolean): TextDirectionHeuristic? =
    when {
      !writingDirection.resolvesWordMajority -> null
      layoutRtl -> rtl
      hasRightToLeft -> ltr
      else -> TextDirectionHeuristics.FIRSTSTRONG_LTR
    }
}

/** Paragraphs without letters take the layout direction, as with FIRSTSTRONG_LTR / _RTL. */
private class WordMajorityHeuristic(
  private val defaultIsRtl: Boolean,
  private val inlineCode: InlineCodeParagraphs,
) : TextDirectionHeuristic {
  override fun isRtl(
    array: CharArray,
    start: Int,
    count: Int,
  ): Boolean = isRtl(CharBuffer.wrap(array), start, count)

  override fun isRtl(
    cs: CharSequence,
    start: Int,
    count: Int,
  ): Boolean = inlineCode.isRtl(cs, start, start + count) ?: defaultIsRtl
}

/**
 * Leaves inline code out of the count, as Onicode's desktop does. StaticLayout hands the heuristic a
 * bare copy of each paragraph, so the paragraphs holding inline code are resolved ahead from the
 * spans and looked up by that copy: the paragraph's characters, its newline included, with
 * ReplacementSpan ranges as U+FFFC (MeasuredParagraph). Paragraphs with the same copy but different
 * answers cannot be told apart, so they all count their inline code.
 */
private class InlineCodeParagraphs {
  private var directions: Map<String, Boolean?> = emptyMap()

  fun read(text: CharSequence?) {
    directions = if (text is Spanned) inlineCodeParagraphDirections(text) else emptyMap()
  }

  fun isRtl(
    text: CharSequence,
    start: Int,
    end: Int,
  ): Boolean? {
    if (text is Spanned) return proseIsRtl(text, start, end)
    if (directions.isNotEmpty()) {
      val key = text.subSequence(start, end).toString()
      if (directions.containsKey(key)) return directions[key]
    }
    return wordMajorityIsRtl(text, start, end)
  }
}

private fun inlineCodeParagraphDirections(text: Spanned): Map<String, Boolean?> {
  val codeSpans = text.getSpans(0, text.length, CodeSpan::class.java)
  if (codeSpans.isEmpty()) return emptyMap()
  val directions = HashMap<String, Boolean?>()
  val ambiguous = HashSet<String>()

  fun record(
    key: String,
    isRtl: Boolean?,
  ) {
    if (!directions.containsKey(key)) {
      directions[key] = isRtl
    } else if (directions[key] != isRtl) {
      ambiguous.add(key)
    }
  }

  val codeStarts = IntArray(codeSpans.size) { text.getSpanStart(codeSpans[it]) }
  codeStarts.sort()
  val codeParagraphs = HashSet<Int>()
  var covered = 0
  for (codeStart in codeStarts) {
    if (codeStart < covered) continue
    var start = codeStart
    while (start > 0 && text[start - 1] != '\n') start--
    val end = paragraphEnd(text, codeStart)
    covered = end
    codeParagraphs.add(start)
    val chars = paragraphCopy(text, start, end)
    // Keyed before maskedIsRtl blanks the code in chars.
    val key = String(chars)
    record(key, maskedIsRtl(text, start, chars))
  }

  // A plain paragraph is looked up by the same copy, so it must not take a code paragraph's answer.
  val keyLengths = directions.keys.mapTo(HashSet()) { it.length }
  var start = 0
  while (start < text.length) {
    val end = paragraphEnd(text, start)
    if (start !in codeParagraphs && (end - start) in keyLengths) {
      val chars = paragraphCopy(text, start, end)
      val key = String(chars)
      // Only a clash matters; an unmatched plain paragraph is counted on lookup, not kept.
      if (directions.containsKey(key)) {
        val isRtl = wordMajorityIsRtl(CharBuffer.wrap(chars), 0, chars.size)
        if (directions[key] != isRtl) ambiguous.add(key)
      }
    }
    start = end
  }
  directions.keys.removeAll(ambiguous)
  return directions
}

/** The end of the paragraph holding [index], after its newline. */
private fun paragraphEnd(
  text: CharSequence,
  index: Int,
): Int {
  var end = index
  while (end < text.length && text[end] != '\n') end++
  return if (end < text.length) end + 1 else end
}

/** Word majority of [text] in [start, end) with inline code read as whitespace. */
private fun proseIsRtl(
  text: Spanned,
  start: Int,
  end: Int,
): Boolean? = maskedIsRtl(text, start, paragraphCopy(text, start, end))

private fun paragraphCopy(
  text: Spanned,
  start: Int,
  end: Int,
): CharArray {
  val chars = CharArray(end - start)
  TextUtils.getChars(text, start, end, chars, 0)
  fill(text, start, end, chars, ReplacementSpan::class.java, '\uFFFC')
  return chars
}

private fun maskedIsRtl(
  text: Spanned,
  start: Int,
  chars: CharArray,
): Boolean? {
  fill(text, start, start + chars.size, chars, CodeSpan::class.java, ' ')
  return wordMajorityIsRtl(CharBuffer.wrap(chars), 0, chars.size)
}

private fun <T : Any> fill(
  text: Spanned,
  start: Int,
  end: Int,
  chars: CharArray,
  kind: Class<T>,
  with: Char,
) {
  for (span in text.getSpans(start, end, kind)) {
    val from = (text.getSpanStart(span) - start).coerceAtLeast(0)
    val to = (text.getSpanEnd(span) - start).coerceAtMost(chars.size)
    if (from < to) chars.fill(with, from, to)
  }
}

/**
 * Whether [text] holds a right-to-left letter, or a character first-strong reads as right to left.
 * Without one, every paragraph resolves left to right in a left-to-right layout under either rule.
 */
private fun hasRightToLeftCharacter(text: CharSequence): Boolean {
  var i = 0
  while (i < text.length) {
    if (text[i] < '\u0590') {
      i++
      continue
    }
    val codePoint = Character.codePointAt(text, i)
    if (isRightToLeftScalar(codePoint)) return true
    when (Character.getDirectionality(codePoint)) {
      Character.DIRECTIONALITY_RIGHT_TO_LEFT,
      Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
      Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
      Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
      -> return true
    }
    i += Character.charCount(codePoint)
  }
  return false
}

private class DirectionTally {
  var rtl = 0
  var ltr = 0
  var first: Boolean? = null

  fun add(isRtl: Boolean?) {
    if (isRtl == null) return
    if (first == null) first = isRtl
    if (isRtl) rtl++ else ltr++
  }

  fun majority(): Boolean? = if (rtl == ltr) first else rtl > ltr
}

/**
 * Whether [text] in [start, end) reads right to left, or null when it has no letters.
 * Each sentence follows most of its words and the text follows most of its sentences;
 * a sentence tie goes to the word majority, then the first word. Mirrors Onicode's
 * `textDirection` (packages/shared/src/textDirection.ts) and the iOS ENRMWordMajorityDirection.
 */
internal fun wordMajorityIsRtl(
  text: CharSequence,
  start: Int,
  end: Int,
): Boolean? {
  val sentences = DirectionTally()
  val words = DirectionTally()
  var sentence = DirectionTally()
  // A word takes the direction of its first letter.
  var word: Boolean? = null

  var i = start
  while (i < end) {
    val unit = text[i]
    if (isJsWhitespace(unit)) {
      sentence.add(word)
      words.add(word)
      word = null
      var sentenceEnds = followsSentenceEnd(text, start, i)
      while (i < end && isJsWhitespace(text[i])) {
        if (text[i] == '\n') sentenceEnds = true
        i++
      }
      if (sentenceEnds) {
        sentences.add(sentence.majority())
        sentence = DirectionTally()
      }
      continue
    }
    var codePoint = unit.code
    var step = 1
    if (Character.isHighSurrogate(unit) && i + 1 < end && Character.isLowSurrogate(text[i + 1])) {
      codePoint = Character.toCodePoint(unit, text[i + 1])
      step = 2
    }
    if (word == null && Character.isLetter(codePoint)) word = isRightToLeftScalar(codePoint)
    i += step
  }
  sentence.add(word)
  words.add(word)
  sentences.add(sentence.majority())

  return if (sentences.rtl == sentences.ltr) words.majority() else sentences.rtl > sentences.ltr
}

// Hebrew through Arabic Extended-A, the presentation forms, and the two Plane 1
// ranges Unicode reserves for right-to-left scripts.
private fun isRightToLeftScalar(codePoint: Int): Boolean =
  codePoint in 0x0590..0x08FF ||
    codePoint in 0xFB1D..0xFDFF ||
    codePoint in 0xFE70..0xFEFF ||
    codePoint in 0x10800..0x10FFF ||
    codePoint in 0x1E800..0x1EFFF

// JavaScript's `\s`, which the reference rule splits words on.
private fun isJsWhitespace(unit: Char): Boolean =
  unit in '\u0009'..'\u000D' ||
    unit == ' ' ||
    unit == ' ' ||
    unit == ' ' ||
    unit in ' '..' ' ||
    unit == ' ' ||
    unit == ' ' ||
    unit == ' ' ||
    unit == ' ' ||
    unit == '　' ||
    unit == '﻿'

private fun isAsciiWordUnit(unit: Char): Boolean = unit in 'a'..'z' || unit in 'A'..'Z' || unit in '0'..'9' || unit == '_'

// Whether the whitespace at [index] follows a sentence end: `.`, `!`, `?` or `؟`, but not
// the last dot of a standalone "e.g." or "i.e." (any case).
private fun followsSentenceEnd(
  text: CharSequence,
  start: Int,
  index: Int,
): Boolean {
  if (index - 1 < start) return false
  val last = text[index - 1]
  if (last == '!' || last == '?' || last == '؟') return true
  if (last != '.') return false
  if (index - 4 < start || text[index - 3] != '.') return true
  val head = text[index - 4].lowercaseAscii()
  val tail = text[index - 2].lowercaseAscii()
  val abbreviation = (head == 'e' && tail == 'g') || (head == 'i' && tail == 'e')
  if (!abbreviation) return true
  return index - 5 >= start && isAsciiWordUnit(text[index - 5])
}

private fun Char.lowercaseAscii(): Char = if (this in 'A'..'Z') this + 32 else this
