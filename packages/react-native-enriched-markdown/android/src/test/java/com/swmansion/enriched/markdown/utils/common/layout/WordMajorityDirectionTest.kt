package com.swmansion.enriched.markdown.utils.common.layout

import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.swmansion.enriched.markdown.parser.MarkdownASTNode
import com.swmansion.enriched.markdown.parser.MarkdownASTNode.NodeType
import com.swmansion.enriched.markdown.test.MarkdownRenderTestSupport.render
import com.swmansion.enriched.markdown.test.MarkdownRenderTestSupport.styleConfig
import com.swmansion.enriched.markdown.test.TestAstFactory.paragraph
import com.swmansion.enriched.markdown.test.TestAstFactory.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordMajorityDirectionTest {
  private fun direction(text: String) = wordMajorityIsRtl(text, 0, text.length)

  @Test
  fun followsMostWords() {
    assertEquals(true, direction("Plantur هذا شرح عربي طويل للمنتج"))
    assertEquals(true, direction("https://example.com هذا شرح عربي طويل للرابط"))
    assertEquals(false, direction("مرحبا then run the build and tests."))
    // A tie goes to the first word.
    assertEquals(false, direction("Hello مرحبا"))
    assertEquals(true, direction("مرحبا Hello"))
    assertEquals(null, direction("123 456."))
    // Directional marks are not letters.
    assertEquals(null, direction("\u200F123"))
  }

  @Test
  fun countsSentencesBeforeWords() {
    assertEquals(true, direction("This is one long English sentence with many words. مرحبا بك. شكرا لك."))
    // "e.g." does not end a sentence.
    assertEquals(true, direction("See e.g. this long English example here. مرحبا بك. شكرا لك."))
    // A sentence tie goes to the word count.
    assertEquals(true, direction("Plantur، Haarserum 100 مل، 14,95 €. سيروم مخصص للشعر الطويل بالكافيين، أغلى واحد في القائمة."))
  }

  // Onicode's apps/web/src/textDirection.test.ts, case for case, so the platforms cannot drift apart.
  @Test
  fun matchesOnicodeDesktop() {
    val cases =
      listOf(
        "اكتب دالة تجمع الأرقام" to true,
        "תקן את הבאג" to true,
        "fix the failing test" to false,
        "StrictMode: يعيد الـ render مرتين في التطوير" to true,
        "translate the label مرحبا for the login screen" to false,
        "Done. تم إصلاح المشكلة في الملف الرئيسي" to true,
        "React مكتبة" to false,
        "مكتبة React" to true,
        "عدّل السطر 42، ثم شغّل الاختبارات (3)" to true,
        "هذا مثال على paragraph يحتوي على نص عربي و English words معًا. Arabic text should flow right-to-left while " +
          "English words like JavaScript, React, and API should remain left-to-right within the same line. الأرقام " +
          "1234567890 ورموز مثل @username و #hashtag و 100$ تظهر في الاتجاه الصحيح. هذه جملة لاختبار RTL rendering " +
          "في المتصفح أو المحرر." to true,
        "Run the migration first. ثم شغّل الاختبارات كلها. Then deploy to staging." to false,
        "هذه جملة عربية تتحدث عن e.g. React i.e. TypeScript JavaScript" to true,
        "This long English sentence describes the technology sector in the U.S. هذه جملة عربية. وهذه جملة عربية أخرى." to true,
      )
    for ((text, rtl) in cases) assertEquals(text, rtl, direction(text))
    assertEquals(null, direction("42 — 7 ✓"))
  }

  @Test
  fun readsOnlyTheGivenRange() {
    val text = "Hello world again\nPlantur هذا شرح عربي"
    val paragraphStart = text.indexOf('\n') + 1
    assertEquals(false, wordMajorityIsRtl(text, 0, paragraphStart))
    assertEquals(true, wordMajorityIsRtl(text, paragraphStart, text.length))
  }

  @Test
  fun leavesInlineCodeOutOfTheCount() {
    val arabic = render(styleConfig(), paragraph(text("استخدم "), code("npm run build"), text(" ثم "), code("npm test")))
    assertEquals(Layout.DIR_RIGHT_TO_LEFT, measure(arabic).getParagraphDirection(0))

    val hebrew =
      render(styleConfig(), paragraph(text("לערוך את "), code("apps/web/src/chat.tsx"), text(" או להשאיר "), code("useMemo"), text("?")))
    assertEquals(Layout.DIR_RIGHT_TO_LEFT, measure(hebrew).getParagraphDirection(0))

    val english = render(styleConfig(), paragraph(text("Should I edit "), code("useMemo"), text(" or keep it? شكرا")))
    assertEquals(Layout.DIR_LEFT_TO_RIGHT, measure(english).getParagraphDirection(0))

    // A view reading the same text hands StaticLayout the same answer for the paragraph's bare copy.
    val view = MarkdownTextDirection()
    view.update(MarkdownWritingDirection.WORD_MAJORITY, arabic)
    val newline = arabic.indexOf('\n')
    val paragraph = arabic.substring(0, if (newline < 0) arabic.length else newline + 1).toCharArray()
    assertEquals(true, view.heuristic(layoutRtl = false)!!.isRtl(paragraph, 0, paragraph.size))
  }

  @Test
  fun textWithoutRightToLeftCharactersKeepsThePlatformHeuristic() {
    val view = MarkdownTextDirection()
    view.update(MarkdownWritingDirection.WORD_MAJORITY, "Plain English, with “quotes” and 😀.")
    assertSame(TextDirectionHeuristics.FIRSTSTRONG_LTR, view.heuristic(layoutRtl = false))
    // A right-to-left mark alone would turn first-strong right to left.
    assertEquals(true, view.update(MarkdownWritingDirection.WORD_MAJORITY, "\u200F123"))
  }

  private fun code(content: String) = MarkdownASTNode(NodeType.Code, children = listOf(text(content)))

  private fun measure(text: CharSequence): StaticLayout =
    StaticLayout.Builder
      .obtain(text, 0, text.length, TextPaint().apply { textSize = 16f }, 10000)
      .setWritingDirection(MarkdownWritingDirection.WORD_MAJORITY, text)
      .build()
}
