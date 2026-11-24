#import "../../ios/parser/ENRMMarkdownParser.h"
#import "../../ios/parser/MarkdownASTNode.h"
#import "../../ios/renderer/ListItemRenderer.h"
#import "../../ios/renderer/RenderContext.h"
#import "../../ios/segments/ENRMTextRenderCache.h"
#import "../../ios/segments/RenderedMarkdownSegment.h"
#import "../../ios/segments/SegmentRenderer.h"
#import "../../ios/segments/TableContainerView.h"
#import "../../ios/styles/StyleConfig.h"
#import "../../ios/utils/ENRMTextRenderer.h"
#import "../../ios/utils/HTMLGenerator.h"
#import <XCTest/XCTest.h>

static StyleConfig *ENRMLiftedConfig(void)
{
  StyleConfig *config = [[StyleConfig alloc] init];
  [config setListStyleFontSize:16];
  [config setListStyleMarginLeft:24];
  [config setListStyleMarginTop:16];
  [config setListStyleMarginBottom:16];
  return config;
}

static MarkdownASTNode *ENRMLiftedParse(NSString *markdown)
{
  return [[[ENRMMarkdownParser alloc] init] parseMarkdown:markdown flags:[ENRMMd4cFlags defaultFlags] isGFM:YES];
}

static NSArray<ENRMRenderedSegment *> *ENRMRenderLiftedTables(NSString *markdown)
{
  return ENRMRenderSegmentsFromAST(ENRMLiftedParse(markdown), ENRMLiftedConfig(), YES, YES, 0,
                                   NSLineBreakStrategyStandard, NO, 0);
}

static NSArray<ENRMRenderedSegment *> *ENRMRenderLiftedTablesCached(NSString *markdown, ENRMTextRenderCache *cache)
{
  [cache beginRenderWithKey:@[ @1 ]];
  NSArray<ENRMRenderedSegment *> *segments = ENRMRenderSegmentsFromASTCached(
      ENRMLiftedParse(markdown), ENRMLiftedConfig(), YES, YES, 0, NSLineBreakStrategyStandard,
      ENRMWritingDirectionModeWordMajority, NSWritingDirectionLeftToRight, cache);
  [cache endRender];
  return segments;
}

static NSString *ENRMLiftedSegmentKinds(NSArray<ENRMRenderedSegment *> *segments)
{
  NSMutableArray *kinds = [NSMutableArray array];
  for (ENRMRenderedSegment *segment in segments) {
    [kinds addObject:segment.kind == ENRMSegmentKindTable  ? @"table"
                     : segment.kind == ENRMSegmentKindText ? @"text"
                                                           : @"?"];
  }
  return [kinds componentsJoinedByString:@","];
}

static NSRange ENRMLiftedRangeOf(ENRMRenderedSegment *segment, NSString *needle)
{
  return [segment.textResult.attributedText.string rangeOfString:needle];
}

static CGFloat ENRMLiftedHeadIndentAt(ENRMRenderedSegment *segment, NSString *needle)
{
  NSRange range = ENRMLiftedRangeOf(segment, needle);
  NSParagraphStyle *style = [segment.textResult.attributedText attribute:NSParagraphStyleAttributeName
                                                                 atIndex:range.location
                                                          effectiveRange:NULL];
  return style.headIndent;
}

// The markers drawn in a text segment, as "<number>" for ordered and "•" for bullet items.
static NSArray<NSString *> *ENRMLiftedMarkers(ENRMRenderedSegment *segment)
{
  NSMutableArray *markers = [NSMutableArray array];
  NSAttributedString *text = segment.textResult.attributedText;
  [text enumerateAttribute:ListItemMarkerStartAttribute
                   inRange:NSMakeRange(0, text.length)
                   options:0
                usingBlock:^(NSArray<ENRMListMarkerDescriptor *> *value, NSRange range, BOOL *stop) {
                  for (ENRMListMarkerDescriptor *marker in value) {
                    [markers addObject:marker.listType == ListTypeOrdered
                                           ? [NSString stringWithFormat:@"%ld", (long)marker.number]
                                           : @"•"];
                  }
                }];
  return markers;
}

@interface ENRMListTableLiftTests : XCTestCase
@end

@implementation ENRMListTableLiftTests

- (void)testTableInBulletItemBecomesTableSegmentAtItemTextIndent
{
  NSArray<ENRMRenderedSegment *> *segments =
      ENRMRenderLiftedTables(@"- Tickets:\n\n  | Ticket | Content |\n  |---|---|\n  | TT-74 | Remove |\n- Next");

  XCTAssertEqualObjects(ENRMLiftedSegmentKinds(segments), @"text,table,text");
  XCTAssertEqualObjects(ENRMLiftedMarkers(segments[0]), (@[ @"•" ]));
  XCTAssertEqualObjects(ENRMLiftedMarkers(segments[2]), (@[ @"•" ]));
  XCTAssertEqual(ENRMLiftedRangeOf(segments[0], @"Ticket |").location, NSNotFound);
  CGFloat indent = ENRMLiftedHeadIndentAt(segments[0], @"Tickets");
  XCTAssertGreaterThan(indent, 0);
  XCTAssertEqualWithAccuracy(segments[1].tableSegment.lift.indent, indent, 0.001);
  XCTAssertEqualWithAccuracy(ENRMLiftedHeadIndentAt(segments[2], @"Next"), indent, 0.001);
}

- (void)testOrderedListKeepsItsNumbersAroundTheTable
{
  NSArray<ENRMRenderedSegment *> *segments =
      ENRMRenderLiftedTables(@"7. A\n8. B:\n\n   | a | b |\n   |---|---|\n   | c | d |\n9. C\n10. D");

  XCTAssertEqualObjects(ENRMLiftedSegmentKinds(segments), @"text,table,text");
  XCTAssertEqualObjects(ENRMLiftedMarkers(segments[0]), (@[ @"7", @"8" ]));
  XCTAssertEqualObjects(ENRMLiftedMarkers(segments[2]), (@[ @"9", @"10" ]));
  XCTAssertEqualWithAccuracy(ENRMLiftedHeadIndentAt(segments[2], @"C"), ENRMLiftedHeadIndentAt(segments[0], @"B"),
                             0.001);
}

- (void)testContinuationKeepsItsItemNumber
{
  NSArray<ENRMRenderedSegment *> *segments =
      ENRMRenderLiftedTables(@"7. A\n\n   | a |\n   |---|\n   | b |\n\n   After\n8. B");

  NSAttributedString *rest = segments[2].textResult.attributedText;
  XCTAssertEqualObjects([rest attribute:ListItemNumberAttribute
                                   atIndex:[rest.string rangeOfString:@"After"].location
                            effectiveRange:NULL],
                        @7);
  XCTAssertEqualObjects(ENRMLiftedMarkers(segments[2]), (@[ @"8" ]));
}

- (void)testItemOpeningWithTableDrawsItsMarkerOnAnEmptyLine
{
  NSArray<ENRMRenderedSegment *> *segments = ENRMRenderLiftedTables(@"- Before\n- | a |\n  |---|\n  | b |\n- Next");

  XCTAssertEqualObjects(ENRMLiftedSegmentKinds(segments), @"text,table,text");
  XCTAssertTrue([segments[0].textResult.attributedText.string hasSuffix:@"Before\n "]);
  XCTAssertEqualObjects(ENRMLiftedMarkers(segments[0]), (@[ @"•", @"•" ]));
  XCTAssertGreaterThan(segments[1].tableSegment.lift.indent, 0);

  StyleConfig *padded = ENRMLiftedConfig();
  [padded setCodeBlockPadding:16];
  NSArray<ENRMRenderedSegment *> *emptyFence =
      ENRMRenderSegmentsFromAST(ENRMLiftedParse(@"- Before\n- ```\n  ```\n\n  | a |\n  |---|\n  | b |"), padded, YES,
                                YES, 0, NSLineBreakStrategyStandard, NO, 0);
  XCTAssertEqualObjects(ENRMLiftedMarkers(emptyFence[0]), (@[ @"•", @"•" ]),
                        @"an empty code block's spacer line does not count as the item's text");
}

- (void)testItemOpeningWithNestedListDrawsItsMarkerOnThatListsFirstLine
{
  NSArray<ENRMRenderedSegment *> *segments =
      ENRMRenderLiftedTables(@"- Before\n- - Inner:\n\n    | a |\n    |---|\n    | b |\n- Last");

  XCTAssertEqualObjects(ENRMLiftedSegmentKinds(segments), @"text,table,text");
  NSString *head = segments[0].textResult.attributedText.string;
  XCTAssertTrue([head hasSuffix:@"Before\nInner:"], @"%@", head);
  XCTAssertEqualObjects(ENRMLiftedMarkers(segments[0]), (@[ @"•", @"•", @"•" ]));
  XCTAssertEqualWithAccuracy(segments[1].tableSegment.lift.indent, ENRMLiftedHeadIndentAt(segments[0], @"Inner"),
                             0.001);
}

- (void)testWideOrderedListsSplitPiecesShareAStableGutter
{
  NSMutableString *markdown = [NSMutableString stringWithString:@"98. a\n99. b:\n\n    | a |\n    |---|\n    | b |\n"];
  [markdown appendString:@"100. c\n"];
  NSArray<ENRMRenderedSegment *> *three = ENRMRenderLiftedTables(markdown);
  [markdown appendString:@"101. d\n"];
  NSArray<ENRMRenderedSegment *> *four = ENRMRenderLiftedTables(markdown);

  XCTAssertEqualWithAccuracy(ENRMLiftedHeadIndentAt(three[0], @"a"), ENRMLiftedHeadIndentAt(three[2], @"c"), 0.001);
  XCTAssertEqual(three[0].signature, four[0].signature, @"a streamed item does not re-render earlier pieces");
}

- (void)testCachedRenderKeepsTheInset
{
  NSString *markdown = @"- Tickets:\n\n  | a |\n  |---|\n  | b |\n- Next";
  ENRMTextRenderCache *cache = [[ENRMTextRenderCache alloc] init];
  CGFloat first = ENRMRenderLiftedTablesCached(markdown, cache)[1].tableSegment.lift.indent;
  NSArray<ENRMRenderedSegment *> *again = ENRMRenderLiftedTablesCached(markdown, cache);

  XCTAssertGreaterThan(first, 0);
  XCTAssertNil(again[0].textResult.attributedText, @"the cache kept the head without its text");
  XCTAssertEqualWithAccuracy(again[1].tableSegment.lift.indent, first, 0.001);
}

- (void)testItemSpacingStaysAfterAContinuation
{
  StyleConfig *config = ENRMLiftedConfig();
  [config setListStyleItemSpacing:10];
  NSArray<ENRMRenderedSegment *> *segments =
      ENRMRenderSegmentsFromAST(ENRMLiftedParse(@"- A\n\n  | a |\n  |---|\n  | b |\n\n  After\n- Next"), config, YES,
                                YES, 0, NSLineBreakStrategyStandard, NO, 0);

  NSAttributedString *rest = segments[2].textResult.attributedText;
  NSParagraphStyle *after = [rest attribute:NSParagraphStyleAttributeName
                                    atIndex:[rest.string rangeOfString:@"After"].location
                             effectiveRange:NULL];
  XCTAssertEqualWithAccuracy(after.paragraphSpacing, 10, 0.001);
}

- (void)testContinuationExportsAsMarkerlessItem
{
  NSArray<ENRMRenderedSegment *> *segments =
      ENRMRenderLiftedTables(@"3. A\n\n   | a |\n   |---|\n   | b |\n\n   After\n4. B");

  NSString *html = generateHTML(segments[2].textResult.attributedText, ENRMLiftedConfig());
  XCTAssertTrue([html containsString:@"<li value=\"3\" "], @"%@", html);
  XCTAssertTrue([html containsString:@"list-style-type: none;"], @"%@", html);
  XCTAssertTrue([html containsString:@"<li value=\"4\" "], @"%@", html);
}

- (void)testNestedItemTableAndTextAfterItKeepTheirDepth
{
  NSArray<ENRMRenderedSegment *> *segments = ENRMRenderLiftedTables(
      @"- Outer\n  - Inner:\n\n    | a | b |\n    |---|---|\n    | c | d |\n\n    After\n  - Sibling\n- Last");

  XCTAssertEqualObjects(ENRMLiftedSegmentKinds(segments), @"text,table,text");
  XCTAssertEqualObjects(ENRMLiftedMarkers(segments[0]), (@[ @"•", @"•" ]));
  CGFloat outer = ENRMLiftedHeadIndentAt(segments[0], @"Outer");
  CGFloat inner = ENRMLiftedHeadIndentAt(segments[0], @"Inner");
  XCTAssertGreaterThan(inner, outer);
  XCTAssertEqualWithAccuracy(segments[1].tableSegment.lift.indent, inner, 0.001);

  ENRMRenderedSegment *rest = segments[2];
  XCTAssertEqualObjects(ENRMLiftedMarkers(rest), (@[ @"•", @"•" ]), @"Sibling and Last, none for After");
  NSRange after = ENRMLiftedRangeOf(rest, @"After");
  XCTAssertTrue([[rest.textResult.attributedText attribute:ListItemContinuationAttribute
                                                   atIndex:after.location
                                            effectiveRange:NULL] boolValue]);
  XCTAssertEqualWithAccuracy(ENRMLiftedHeadIndentAt(rest, @"After"), inner, 0.001);
  XCTAssertEqualWithAccuracy(ENRMLiftedHeadIndentAt(rest, @"Sibling"), inner, 0.001);
  XCTAssertEqualWithAccuracy(ENRMLiftedHeadIndentAt(rest, @"Last"), outer, 0.001);
}

- (void)testTaskItemStaysOneTask
{
  NSArray<ENRMRenderedSegment *> *segments =
      ENRMRenderLiftedTables(@"- [ ] Check:\n\n  | a |\n  |---|\n  | b |\n\n  Note\n- [x] Done");

  XCTAssertEqualObjects(ENRMLiftedSegmentKinds(segments), @"text,table,text");
  NSMutableArray<NSNumber *> *taskIndices = [NSMutableArray array];
  for (ENRMRenderedSegment *segment in @[ segments[0], segments[2] ]) {
    NSAttributedString *text = segment.textResult.attributedText;
    [text enumerateAttribute:TaskIndexAttribute
                     inRange:NSMakeRange(0, text.length)
                     options:0
                  usingBlock:^(NSNumber *value, NSRange range, BOOL *stop) {
                    if (value && ![taskIndices.lastObject isEqualToNumber:value])
                      [taskIndices addObject:value];
                  }];
  }
  XCTAssertEqualObjects(taskIndices, (@[ @0, @1 ]));
  XCTAssertEqualWithAccuracy(ENRMLiftedHeadIndentAt(segments[2], @"Note"),
                             ENRMLiftedHeadIndentAt(segments[0], @"Check"), 0.001);
}

- (void)testPiecesNextToEachOtherAddNoListMargins
{
  NSArray<ENRMRenderedSegment *> *segments =
      ENRMRenderLiftedTables(@"1. A\n\n   | a |\n   |---|\n   | b |\n\n   After\n2. Next");

  NSString *rest = segments[2].textResult.attributedText.string;
  NSRange after = [rest rangeOfString:@"After"];
  NSRange next = [rest rangeOfString:@"Next"];
  XCTAssertEqualObjects([rest substringWithRange:NSMakeRange(NSMaxRange(after), next.location - NSMaxRange(after))],
                        @"\n");
  XCTAssertTrue([rest hasPrefix:@"After"], @"no top margin right after the table");
}

- (void)testListWithoutTablesStaysOneTextSegment
{
  NSArray<ENRMRenderedSegment *> *segments = ENRMRenderLiftedTables(@"- A\n- B\n\n| a |\n|---|\n| b |");

  XCTAssertEqualObjects(ENRMLiftedSegmentKinds(segments), @"text,table");
  XCTAssertNil(segments[1].tableSegment.liftKey);
  XCTAssertNil(segments[1].tableSegment.lift);
}

- (void)testInsetSideFollowsTheItemsTextDirection
{
  NSArray<ENRMRenderedSegment *> *segments = ENRMRenderLiftedTables(@"- A\n\n  | a |\n  |---|\n  | b |");
  TableContainerView *table = [[TableContainerView alloc] initWithConfig:ENRMLiftedConfig()];
  ENRMLiftedIndent *lift = [[ENRMLiftedIndent alloc] init];
  lift.indent = 30;
  lift.firstStrongDirection = NSWritingDirectionLeftToRight;
  lift.wordMajorityDirection = NSWritingDirectionLeftToRight;
  table.lift = lift;
  table.writingDirectionMode = ENRMWritingDirectionModeWordMajority;
  [table applyTableNode:segments[1].tableSegment.tableNode];
  table.frame = CGRectMake(0, 0, 300, 100);

  [table layoutIfNeeded];
  XCTAssertTrue(CGRectEqualToRect(table.subviews.firstObject.frame, CGRectMake(30, 0, 270, 100)));

  table.resolvedLayoutDirection = NSWritingDirectionRightToLeft;
  [table layoutIfNeeded];
  XCTAssertTrue(CGRectEqualToRect(table.subviews.firstObject.frame, CGRectMake(30, 0, 270, 100)),
                @"left-to-right text keeps its indent on the left in a right-to-left layout");

  ENRMLiftedIndent *hebrew = [[ENRMLiftedIndent alloc] init];
  hebrew.indent = 30;
  hebrew.firstStrongDirection = NSWritingDirectionRightToLeft;
  hebrew.wordMajorityDirection = NSWritingDirectionRightToLeft;
  table.resolvedLayoutDirection = NSWritingDirectionLeftToRight;
  table.lift = hebrew;
  [table layoutIfNeeded];
  XCTAssertTrue(CGRectEqualToRect(table.subviews.firstObject.frame, CGRectMake(0, 0, 270, 100)),
                @"right-to-left text puts it on the right in a left-to-right layout");
}

- (void)testRowLinesOnlySetsTheOuterColumnsFlushAndDrawsNoOuterBorder
{
  MarkdownASTNode *tableNode =
      ENRMRenderLiftedTables(@"| alpha beta gamma | delta epsilon |\n|---|---|\n| c | d |")[0].tableSegment.tableNode;
  CGSize (^tableSize)(NSString *) = ^CGSize(NSString *lines) {
    StyleConfig *config = ENRMLiftedConfig();
    [config setTableBorderWidth:1];
    [config setTableCellPaddingHorizontal:12];
    [config setTableLines:lines];
    TableContainerView *table = [[TableContainerView alloc] initWithConfig:config];
    [table applyTableNode:tableNode];
    table.frame = CGRectMake(0, 0, 300, 100);
    [table layoutIfNeeded];
    UIView *grid = table.subviews.firstObject.subviews.firstObject;
    XCTAssertEqualWithAccuracy([table measureHeight:300], grid.frame.size.height, 0.001);
    return grid.frame.size;
  };

  CGSize grid = tableSize(@"");
  CGSize rows = tableSize(@"rows");
  XCTAssertEqualWithAccuracy(rows.width, grid.width - 2 * 12 - 1, 0.001);
  XCTAssertEqualWithAccuracy(rows.height, grid.height - 1, 0.001);

  StyleConfig *config = ENRMLiftedConfig();
  [config setTableLines:@"rows"];
  NSDictionary * (^cell)(NSString *) = ^NSDictionary *(NSString *text)
  {
    return @{@"attributedText" : [[NSAttributedString alloc] initWithString:text]};
  };
  NSString *html = generateTableHTML(@[ @[ cell(@"a") ], @[ cell(@"b") ], @[ cell(@"c") ] ], config);
  NSArray<NSString *> *cells = [html componentsSeparatedByString:@"<td"];
  XCTAssertEqual(cells.count, 4u);
  XCTAssertTrue([cells[1] containsString:@"border-bottom"]);
  XCTAssertTrue([cells[2] containsString:@"border-bottom"]);
  XCTAssertFalse([cells[3] containsString:@"border-bottom"], @"no line under the last row: %@", html);
}

- (void)testHeadTextDirectionDecidesTheSideAndTheSignature
{
  NSArray<ENRMRenderedSegment *> *prose = ENRMRenderLiftedTables(@"- foo bar שלום\n\n  | a |\n  |---|\n  | b |");
  NSArray<ENRMRenderedSegment *> *code = ENRMRenderLiftedTables(@"- `foo bar` שלום\n\n  | a |\n  |---|\n  | b |");

  XCTAssertEqual(prose[1].tableSegment.lift.wordMajorityDirection, NSWritingDirectionLeftToRight);
  XCTAssertEqual(code[1].tableSegment.lift.wordMajorityDirection, NSWritingDirectionRightToLeft,
                 @"inline code reads as whitespace");
  XCTAssertNotEqual(prose[1].signature, code[1].signature);

  NSArray<ENRMRenderedSegment *> *fence =
      ENRMRenderLiftedTables(@"- ```\n  שלום עולם\n  ```\n\n  | a |\n  |---|\n  | b |");
  XCTAssertEqual(fence[1].tableSegment.lift.firstStrongDirection, NSWritingDirectionLeftToRight,
                 @"a code block head is left to right, as it draws");
  XCTAssertEqual(fence[1].tableSegment.lift.wordMajorityDirection, NSWritingDirectionLeftToRight);
}

- (void)testUpdatingTheTableKeepsItsAlignment
{
  NSArray<ENRMRenderedSegment *> *segments = ENRMRenderLiftedTables(@"| a | b |\n|---|---|\n| c | d |");
  StyleConfig *config = ENRMLiftedConfig();
  [config setTableAlign:@"right"];
  TableContainerView *table = [[TableContainerView alloc] initWithConfig:config];
  [table applyTableNode:segments[0].tableSegment.tableNode];
  table.frame = CGRectMake(0, 0, 300, 100);
  [table layoutIfNeeded];
  UIView *grid = table.subviews.firstObject.subviews.firstObject;
  CGFloat alignedX = grid.frame.origin.x;
  XCTAssertGreaterThan(alignedX, 0);

  [table applyTableNode:segments[0].tableSegment.tableNode];
  [table layoutIfNeeded];
  XCTAssertEqualWithAccuracy(grid.frame.origin.x, alignedX, 0.001, @"an update in place lays the grid out again");
}

- (void)testRowAccessibilityFramesFollowLayout
{
  MarkdownASTNode *tableNode = ENRMRenderLiftedTables(@"| a | b |\n|---|---|\n| c | d |")[0].tableSegment.tableNode;
  TableContainerView *table = [[TableContainerView alloc] initWithConfig:ENRMLiftedConfig()];
  [table applyTableNode:tableNode];
  UIAccessibilityElement *beforeLayout = table.accessibilityElements.firstObject;
  XCTAssertFalse(CGRectIsNull(beforeLayout.accessibilityFrameInContainerSpace));

  table.frame = CGRectMake(0, 0, 300, 100);
  [table layoutIfNeeded];
  UIAccessibilityElement *row = table.accessibilityElements.firstObject;
  XCTAssertGreaterThan(row.accessibilityFrameInContainerSpace.size.width, 0,
                       @"the rows laid out before the table had a size rebuild once it has one");
}

- (void)testContinuationAfterItsItemCopiesIntoThatItem
{
  NSArray<ENRMRenderedSegment *> *segments =
      ENRMRenderLiftedTables(@"- [x] Check:\n\n  | a |\n  |---|\n  | b |\n\n  After 1\n\n  After 2\n- [ ] Next");

  NSString *html = generateHTML(segments[2].textResult.attributedText, ENRMLiftedConfig());
  XCTAssertEqual([html componentsSeparatedByString:@"<li"].count - 1, 2u, @"%@", html);
  XCTAssertTrue([html containsString:@"After 1<br>"] || [html containsString:@"<br>After 2"], @"%@", html);
  XCTAssertEqual([html componentsSeparatedByString:@"list-style-type: none;"].count - 1, 2u,
                 @"no bullet on the continuation or the task item: %@", html);
}

@end
