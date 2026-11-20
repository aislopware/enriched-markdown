#import "CodeBackground.h"
#import "LastElementUtils.h"
#import "ParagraphStyleUtils.h"
#import <XCTest/XCTest.h>

@interface ENRMWritingDirectionTests : XCTestCase
@end

@implementation ENRMWritingDirectionTests

- (void)testFirstStrongDetectsRightToLeftScripts
{
  XCTAssertEqual(ENRMFirstStrongDirection(@"مرحبا بالعالم"), NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMFirstStrongDirection(@"שלום עולם"), NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMFirstStrongDirection(@"سلام دنیا"), NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMFirstStrongDirection(@"\U0001E900\U0001E901"), NSWritingDirectionRightToLeft, @"Adlam");
  XCTAssertEqual(ENRMFirstStrongDirection(@"\U00010C80\U00010C81"), NSWritingDirectionRightToLeft, @"Old Hungarian");
  XCTAssertEqual(ENRMFirstStrongDirection(@"\U00010900\U00010901"), NSWritingDirectionRightToLeft, @"Phoenician");
  XCTAssertEqual(ENRMFirstStrongDirection(@"\U0001EE00\U0001EE01"), NSWritingDirectionRightToLeft,
                 @"Arabic mathematical alphabet");
}

- (void)testFirstStrongDetectsLeftToRightScripts
{
  XCTAssertEqual(ENRMFirstStrongDirection(@"Hello"), NSWritingDirectionLeftToRight);
  XCTAssertEqual(ENRMFirstStrongDirection(@"こんにちは"), NSWritingDirectionLeftToRight);
  XCTAssertEqual(ENRMFirstStrongDirection(@"\U00010400\U00010401"), NSWritingDirectionLeftToRight, @"Deseret");
  XCTAssertEqual(ENRMFirstStrongDirection(@"\U00011005\U00011006"), NSWritingDirectionLeftToRight, @"Brahmi");
  XCTAssertEqual(ENRMFirstStrongDirection(@"\U0001E7E0\U0001E7E1"), NSWritingDirectionLeftToRight,
                 @"Ethiopic Extended-B");
}

- (void)testFirstStrongSkipsLeadingNeutralCharacters
{
  XCTAssertEqual(ENRMFirstStrongDirection(@"123 مرحبا"), NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMFirstStrongDirection(@"(\"Hello\") مرحبا"), NSWritingDirectionLeftToRight);
  XCTAssertEqual(ENRMFirstStrongDirection(@"😀 سلام"), NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMFirstStrongDirection(@"\u2764\uFE0F مرحبا"), NSWritingDirectionRightToLeft,
                 @"emoji variation selector");
  XCTAssertEqual(ENRMFirstStrongDirection(@"\U0001E944Hello"), NSWritingDirectionLeftToRight, @"Adlam combining mark");
}

- (void)testFirstStrongHonorsDirectionalMarks
{
  XCTAssertEqual(ENRMFirstStrongDirection(@"\u200F123"), NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMFirstStrongDirection(@"\u061C123"), NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMFirstStrongDirection(@"\u200Eمرحبا"), NSWritingDirectionLeftToRight);
}

- (void)testFirstStrongIsNaturalWithoutStrongCharacters
{
  XCTAssertEqual(ENRMFirstStrongDirection(@"123 456."), NSWritingDirectionNatural);
  XCTAssertEqual(ENRMFirstStrongDirection(@"\U0001F600"), NSWritingDirectionNatural);
  XCTAssertEqual(ENRMFirstStrongDirection(@""), NSWritingDirectionNatural);
}

- (void)testParagraphsResolveFromTheirOwnContent
{
  NSMutableAttributedString *output = [[NSMutableAttributedString alloc]
      initWithString:@"😀 Hello\n🙂🙂 مرحبا\nשלום\n\u200F123\n\U0001E900\U0001E901"];

  ENRMApplyFirstStrongParagraphDirections(output, NSWritingDirectionLeftToRight);

  XCTAssertEqual([self directionOf:@"Hello" in:output], NSWritingDirectionLeftToRight);
  XCTAssertEqual([self directionOf:@"مرحبا" in:output], NSWritingDirectionRightToLeft);
  XCTAssertEqual([self directionOf:@"שלום" in:output], NSWritingDirectionRightToLeft);
  XCTAssertEqual([self directionOf:@"\u200F123" in:output], NSWritingDirectionRightToLeft);
  XCTAssertEqual([self directionOf:@"\U0001E900" in:output], NSWritingDirectionRightToLeft);
}

- (void)testNeutralParagraphFallsBackToTheLayoutDirection
{
  NSMutableAttributedString *output = [[NSMutableAttributedString alloc] initWithString:@"Hello\n123"];

  ENRMApplyFirstStrongParagraphDirections(output, NSWritingDirectionRightToLeft);

  XCTAssertEqual([self directionOf:@"Hello" in:output], NSWritingDirectionLeftToRight);
  XCTAssertEqual([self directionOf:@"123" in:output], NSWritingDirectionRightToLeft);
}

- (void)testCodeBlocksKeepTheirDirection
{
  NSMutableAttributedString *output = [[NSMutableAttributedString alloc] initWithString:@"مرحبا"];
  [output addAttribute:CodeBlockAttributeName value:@YES range:NSMakeRange(0, output.length)];

  ENRMApplyFirstStrongParagraphDirections(output, NSWritingDirectionRightToLeft);

  XCTAssertEqual([self directionOf:@"مرحبا" in:output], NSWritingDirectionNatural);
}

- (void)testWordMajorityFollowsMostWords
{
  XCTAssertEqual(ENRMWordMajorityDirection(@"Plantur هذا شرح عربي طويل للمنتج"), NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMWordMajorityDirection(@"https://example.com هذا شرح عربي طويل للرابط"),
                 NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMWordMajorityDirection(@"مرحبا then run the build and tests."), NSWritingDirectionLeftToRight);
  XCTAssertEqual(ENRMWordMajorityDirection(@"Hello مرحبا"), NSWritingDirectionLeftToRight,
                 @"tie goes to the first word");
  XCTAssertEqual(ENRMWordMajorityDirection(@"مرحبا Hello"), NSWritingDirectionRightToLeft,
                 @"tie goes to the first word");
  XCTAssertEqual(ENRMWordMajorityDirection(@"123 456."), NSWritingDirectionNatural);
  XCTAssertEqual(ENRMWordMajorityDirection(@"\u200F123"), NSWritingDirectionNatural, @"marks are not letters");
}

- (void)testWordMajorityCountsSentencesBeforeWords
{
  XCTAssertEqual(ENRMWordMajorityDirection(@"This is one long English sentence with many words. مرحبا بك. شكرا لك."),
                 NSWritingDirectionRightToLeft);
  XCTAssertEqual(ENRMWordMajorityDirection(@"See e.g. this long English example here. مرحبا بك. شكرا لك."),
                 NSWritingDirectionRightToLeft, @"e.g. does not end a sentence");
  XCTAssertEqual(ENRMWordMajorityDirection(@"Plantur، Haarserum 100 مل، 14,95 €. سيروم مخصص للشعر الطويل "
                                           @"بالكافيين، أغلى واحد في القائمة."),
                 NSWritingDirectionRightToLeft, @"a sentence tie goes to the word count");
}

- (void)testWordMajorityParagraphsResolveFromTheirOwnContent
{
  NSMutableAttributedString *output =
      [[NSMutableAttributedString alloc] initWithString:@"Plantur هذا شرح عربي\nمرحبا then run the build\n123"];

  ENRMApplyWordMajorityParagraphDirections(output, NSWritingDirectionRightToLeft);

  XCTAssertEqual([self directionOf:@"Plantur" in:output], NSWritingDirectionRightToLeft);
  XCTAssertEqual([self directionOf:@"مرحبا then" in:output], NSWritingDirectionLeftToRight);
  XCTAssertEqual([self directionOf:@"123" in:output], NSWritingDirectionRightToLeft);
}

// Onicode's apps/web/src/textDirection.test.ts, case for case, so the platforms cannot drift apart.
- (void)testWordMajorityMatchesOnicodeDesktop
{
  NSWritingDirection rtl = NSWritingDirectionRightToLeft;
  NSWritingDirection ltr = NSWritingDirectionLeftToRight;
  NSArray<NSArray *> *cases = @[
    @[ @"اكتب دالة تجمع الأرقام", @(rtl) ],
    @[ @"תקן את הבאג", @(rtl) ],
    @[ @"fix the failing test", @(ltr) ],
    @[ @"StrictMode: يعيد الـ render مرتين في التطوير", @(rtl) ],
    @[ @"translate the label مرحبا for the login screen", @(ltr) ],
    @[ @"Done. تم إصلاح المشكلة في الملف الرئيسي", @(rtl) ],
    @[ @"React مكتبة", @(ltr) ],
    @[ @"مكتبة React", @(rtl) ],
    @[ @"عدّل السطر 42، ثم شغّل الاختبارات (3)", @(rtl) ],
    @[
      @"هذا مثال على paragraph يحتوي على نص عربي و English words معًا. Arabic text should flow right-to-left while "
      @"English words like JavaScript, React, and API should remain left-to-right within the same line. الأرقام "
      @"1234567890 ورموز مثل @username و #hashtag و 100$ تظهر في الاتجاه الصحيح. هذه جملة لاختبار RTL rendering "
      @"في المتصفح أو المحرر.",
      @(rtl)
    ],
    @[ @"Run the migration first. ثم شغّل الاختبارات كلها. Then deploy to staging.", @(ltr) ],
    @[ @"هذه جملة عربية تتحدث عن e.g. React i.e. TypeScript JavaScript", @(rtl) ],
    @[
      @"This long English sentence describes the technology sector in the U.S. هذه جملة عربية. وهذه جملة عربية أخرى.",
      @(rtl)
    ],
    @[ @"ראה https://github.com/aislopware/onicode/pull/24050 לפני", @(rtl) ],
    @[ @"Please check the שלום label before the release", @(ltr) ],
  ];
  for (NSArray *testCase in cases) {
    XCTAssertEqual(ENRMWordMajorityDirection(testCase[0]), (NSWritingDirection)[testCase[1] integerValue], @"%@",
                   testCase[0]);
  }
  XCTAssertEqual(ENRMWordMajorityDirection(@"42 — 7 ✓"), NSWritingDirectionNatural);
}

- (void)testWordMajorityLeavesInlineCodeOut
{
  NSMutableAttributedString *output =
      [self stringWithInlineCode:
                @"استخدم `npm run build` ثم `npm test`\nלערוך את `apps/web/src/chat.tsx` או להשאיר "
                @"`useMemo`?\nShould I edit `useMemo` or keep it?\n`npm test`"];

  ENRMApplyWordMajorityParagraphDirections(output, NSWritingDirectionRightToLeft);

  XCTAssertEqual([self directionOf:@"استخدم" in:output], NSWritingDirectionRightToLeft);
  XCTAssertEqual([self directionOf:@"לערוך" in:output], NSWritingDirectionRightToLeft);
  XCTAssertEqual([self directionOf:@"Should" in:output], NSWritingDirectionLeftToRight);
  NSParagraphStyle *codeOnly = [output attribute:NSParagraphStyleAttributeName
                                         atIndex:output.length - 1
                                  effectiveRange:nil];
  XCTAssertEqual(codeOnly.baseWritingDirection, NSWritingDirectionRightToLeft,
                 @"a paragraph of only inline code takes the fallback");
}

// Inline code between backticks, marked as CodeRenderer marks it.
- (NSMutableAttributedString *)stringWithInlineCode:(NSString *)markdown
{
  NSMutableAttributedString *output = [[NSMutableAttributedString alloc] init];
  NSArray<NSString *> *parts = [markdown componentsSeparatedByString:@"`"];
  for (NSUInteger i = 0; i < parts.count; i++) {
    NSDictionary *attributes = i % 2 == 1 ? @{CodeAttributeName : @YES} : @{};
    [output appendAttributedString:[[NSAttributedString alloc] initWithString:parts[i] attributes:attributes]];
  }
  return output;
}

- (NSWritingDirection)directionOf:(NSString *)text in:(NSAttributedString *)output
{
  NSRange range = [output.string rangeOfString:text];
  XCTAssertNotEqual(range.location, (NSUInteger)NSNotFound, @"%@ was not found", text);
  NSParagraphStyle *style = [output attribute:NSParagraphStyleAttributeName atIndex:range.location effectiveRange:nil];
  return style ? style.baseWritingDirection : NSWritingDirectionNatural;
}

@end
