#import "SegmentRenderer.h"
#import "ENRMFeatureFlags.h"
#import "ENRMTextRenderCache.h"
#import "ENRMTextRenderer.h"
#import "ListItemRenderer.h"
#import "MarkdownASTNode.h"
#import "ParagraphStyleUtils.h"
#import "RenderContext.h"
#import "RenderedMarkdownSegment.h"
#import "StyleConfig.h"

static inline uint64_t ENRMMixString(uint64_t hash, NSString *string)
{
  const char *utf8 = [string UTF8String];
  while (utf8 && *utf8) {
    hash ^= (uint8_t)*utf8++;
    hash *= 1099511628211ULL;
  }
  return hash;
}

// The source an image loads comes from the `imageSources` prop, not the AST, so
// it joins the signature: the reconciler keeps a view whose signature matches
// as it is, and that view must not hold the previous source's attachment.
static uint64_t ENRMMixImageSourceOverrides(uint64_t hash, NSArray<MarkdownASTNode *> *nodes,
                                            NSDictionary<NSString *, ENRMImageSourceOverride *> *overrides)
{
  for (MarkdownASTNode *node in nodes) {
    NSString *occurrenceId = node.attributes[@"imageOccurrenceId"];
    ENRMImageSourceOverride *override = occurrenceId ? overrides[occurrenceId] : nil;
    if (override) {
      hash = ENRMMixString(hash, override.uri);
      for (NSString *key in [override.headers.allKeys sortedArrayUsingSelector:@selector(compare:)]) {
        hash = ENRMMixString(hash, key);
        hash = ENRMMixString(hash, override.headers[key]);
      }
    }
    hash = ENRMMixImageSourceOverrides(hash, node.children, overrides);
  }
  return hash;
}

// Mixes the first task index into the signature, so a segment re-renders when earlier tasks change.
static uint64_t ENRMMixTaskIndexOffset(uint64_t signature, NSInteger taskIndexOffset)
{
  uint64_t value = (uint64_t)taskIndexOffset;
  for (int byte = 0; byte < 8; byte++) {
    signature ^= (value >> (byte * 8)) & 0xFF;
    signature *= 1099511628211ULL;
  }
  return signature;
}

// Counts task items as ListItemRenderer does: a quote's children render later, in its own view.
static NSInteger ENRMCountTaskItems(MarkdownASTNode *node)
{
  NSInteger count = (node.type == MarkdownNodeTypeListItem && [node.attributes[@"isTask"] isEqualToString:@"true"] &&
                     ![node.attributes[ENRMListContinuationNodeAttribute] isEqualToString:@"true"])
                        ? 1
                        : 0;
  for (MarkdownASTNode *child in node.children) {
    count += ENRMCountTaskItems(child);
  }
  return count;
}

static BOOL ENRMIsListNode(MarkdownASTNode *node)
{
  return node.type == MarkdownNodeTypeOrderedList || node.type == MarkdownNodeTypeUnorderedList;
}

static BOOL ENRMListHasLiftedTable(MarkdownASTNode *list);

// A child that ends an item's head: a table, or a nested list holding one.
static BOOL ENRMIsLiftingChild(MarkdownASTNode *child)
{
  return child.type == MarkdownNodeTypeTable || (ENRMIsListNode(child) && ENRMListHasLiftedTable(child));
}

static BOOL ENRMListHasLiftedTable(MarkdownASTNode *list)
{
  for (MarkdownASTNode *item in list.children) {
    if (item.type != MarkdownNodeTypeListItem)
      continue;
    for (MarkdownASTNode *child in item.children) {
      if (ENRMIsLiftingChild(child))
        return YES;
    }
  }
  return NO;
}

static MarkdownASTNode *ENRMCopyNode(MarkdownASTNode *node, NSArray<MarkdownASTNode *> *children)
{
  MarkdownASTNode *copy = [[MarkdownASTNode alloc] initWithType:node.type];
  copy.content = node.content;
  copy.attributes = [node.attributes mutableCopy];
  copy.children = [children mutableCopy];
  return copy;
}

static MarkdownASTNode *ENRMContinuationItem(MarkdownASTNode *item, NSArray<MarkdownASTNode *> *children)
{
  MarkdownASTNode *copy = ENRMCopyNode(item, children);
  [copy.attributes removeObjectForKey:ENRMLiftKeyNodeAttribute];
  copy.attributes[ENRMListContinuationNodeAttribute] = @"true";
  return copy;
}

typedef MarkdownASTNode * (^ENRMListWrap)(MarkdownASTNode *fragment);

// The text renderer cannot draw a table, so a table in a list item becomes a table segment of its own.
// The list is split around it into copies that keep their markers, numbers and nesting; the item's
// text after the table continues in a marker-less continuation copy. `wrap` puts a copy of `list`
// back inside copies of its ancestors. Appends top-level nodes and ENRMTableSegments to `pieces`.
static void ENRMLiftTablesFromList(MarkdownASTNode *list, ENRMListWrap wrap, NSMutableArray *pieces,
                                   NSInteger *nextLiftKey)
{
  const BOOL ordered = list.type == MarkdownNodeTypeOrderedList;
  NSString *startAttr = list.attributes[@"start"];
  __block NSInteger number = (ordered && startAttr) ? MAX((NSInteger)0, startAttr.integerValue) : 1;
  // Ordered markers widen from 100 (ListRenderer); every copy gets the gutter of the list's widest
  // number, rounded up to all nines so it does not change with each item a stream adds.
  NSString *markerNumber = list.attributes[ENRMListMarkerNumberNodeAttribute];
  if (ordered && !markerNumber) {
    NSInteger widest = 0;
    NSInteger itemNumber = number;
    for (MarkdownASTNode *item in list.children) {
      if (item.type != MarkdownNodeTypeListItem)
        continue;
      if (![item.attributes[@"isTask"] isEqualToString:@"true"])
        widest = itemNumber;
      itemNumber++;
    }
    if (widest >= 100) {
      NSInteger nines = 9;
      while (nines < widest)
        nines = nines * 10 + 9;
      markerNumber = [NSString stringWithFormat:@"%ld", (long)nines];
    }
  }
  MarkdownASTNode * (^listCopy)(NSArray<MarkdownASTNode *> *, NSInteger) =
      ^MarkdownASTNode *(NSArray<MarkdownASTNode *> *items, NSInteger start)
  {
    MarkdownASTNode *copy = ENRMCopyNode(list, items);
    if (ordered) {
      copy.attributes[@"start"] = [NSString stringWithFormat:@"%ld", (long)start];
      if (markerNumber)
        copy.attributes[ENRMListMarkerNumberNodeAttribute] = markerNumber;
    }
    return copy;
  };

  NSMutableArray<MarkdownASTNode *> *fragment = [NSMutableArray array];
  __block NSInteger fragmentStart = number;
  // Takes the pending items, plus `last` when given, as one piece through `wrap`.
  MarkdownASTNode * (^takeFragment)(MarkdownASTNode *) = ^MarkdownASTNode *(MarkdownASTNode *last)
  {
    if (last)
      [fragment addObject:last];
    MarkdownASTNode *piece = fragment.count > 0 ? wrap(listCopy(fragment, fragmentStart)) : nil;
    [fragment removeAllObjects];
    fragmentStart = number;
    return piece;
  };
  void (^flushFragment)(void) = ^{
    MarkdownASTNode *piece = takeFragment(nil);
    if (piece)
      [pieces addObject:piece];
  };

  for (MarkdownASTNode *item in list.children) {
    NSUInteger split = NSNotFound;
    if (item.type == MarkdownNodeTypeListItem) {
      split = [item.children indexOfObjectPassingTest:^BOOL(MarkdownASTNode *child, NSUInteger idx, BOOL *stop) {
        return ENRMIsLiftingChild(child);
      }];
    }
    if (split == NSNotFound) {
      [fragment addObject:item];
      if (item.type == MarkdownNodeTypeListItem)
        number++;
      continue;
    }

    NSString *liftKey = [NSString stringWithFormat:@"%ld", (long)(*nextLiftKey)++];
    const NSInteger itemNumber = number;
    number++;
    // An item that opens with a nested list draws its marker on that list's first line: the first piece
    // the nested list gives is wrapped in the item itself, with the items before it.
    __block BOOL headInNestedPiece = split == 0 && item.children[0].type != MarkdownNodeTypeTable;
    if (!headInNestedPiece) {
      // A head with no children (the item opens with the table) renders as an empty line with the marker.
      MarkdownASTNode *headItem = ENRMCopyNode(item, [item.children subarrayWithRange:NSMakeRange(0, split)]);
      headItem.attributes[ENRMLiftKeyNodeAttribute] = liftKey;
      [fragment addObject:headItem];
      flushFragment();
    }

    ENRMListWrap continuationWrap = ^MarkdownASTNode *(MarkdownASTNode *inner)
    {
      if (headInNestedPiece) {
        headInNestedPiece = NO;
        MarkdownASTNode *headItem = ENRMCopyNode(item, @[ inner ]);
        headItem.attributes[ENRMLiftKeyNodeAttribute] = liftKey;
        return takeFragment(headItem);
      }
      return wrap(listCopy(@[ ENRMContinuationItem(item, @[ inner ]) ], itemNumber));
    };
    NSMutableArray<MarkdownASTNode *> *rest = [NSMutableArray array];
    void (^flushRest)(void) = ^{
      if (rest.count > 0) {
        [pieces addObject:wrap(listCopy(@[ ENRMContinuationItem(item, rest) ], itemNumber))];
        [rest removeAllObjects];
      }
    };
    for (NSUInteger index = split; index < item.children.count; index++) {
      MarkdownASTNode *child = item.children[index];
      if (child.type == MarkdownNodeTypeTable) {
        flushRest();
        ENRMTableSegment *table = [ENRMTableSegment segmentWithTableNode:child];
        table.liftKey = liftKey;
        [pieces addObject:table];
      } else if (ENRMIsLiftingChild(child)) {
        flushRest();
        ENRMLiftTablesFromList(child, continuationWrap, pieces, nextLiftKey);
      } else {
        [rest addObject:child];
      }
    }
    flushRest();
  }
  flushFragment();
}

// The pieces ENRMLiftTablesFromList splits `list` into. Only the first keeps the list's top margin and
// only the last its bottom one, so the pieces sit as the list's items did and a table adds just its own.
static NSArray *ENRMListPiecesAroundLiftedTables(MarkdownASTNode *list, NSInteger *nextLiftKey)
{
  NSMutableArray *pieces = [NSMutableArray array];
  ENRMLiftTablesFromList(
      list, ^MarkdownASTNode *(MarkdownASTNode *fragment) { return fragment; }, pieces, nextLiftKey);
  for (NSUInteger index = 0; index < pieces.count; index++) {
    if (![pieces[index] isKindOfClass:[MarkdownASTNode class]])
      continue;
    MarkdownASTNode *node = pieces[index];
    if (index > 0)
      node.attributes[ENRMListNoMarginTopNodeAttribute] = @"true";
    if (index + 1 < pieces.count)
      node.attributes[ENRMListNoMarginBottomNodeAttribute] = @"true";
  }
  return pieces;
}

static NSArray *ENRMSplitASTIntoSegments(MarkdownASTNode *root)
{
  NSMutableArray *segments = [NSMutableArray array];
  NSMutableArray *currentTextNodes = [NSMutableArray array];
  NSInteger nextLiftKey = 0;

  for (MarkdownASTNode *child in root.children) {
    if (ENRMIsListNode(child) && ENRMListHasLiftedTable(child)) {
      for (id piece in ENRMListPiecesAroundLiftedTables(child, &nextLiftKey)) {
        if ([piece isKindOfClass:[ENRMTableSegment class]]) {
          if (currentTextNodes.count > 0) {
            [segments addObject:[ENRMTextSegment segmentWithNodes:[currentTextNodes copy]]];
            [currentTextNodes removeAllObjects];
          }
          [segments addObject:piece];
        } else {
          [currentTextNodes addObject:piece];
        }
      }
      continue;
    }
    NSString *blockSlotId = child.attributes[@"blockSlotId"];
    if (blockSlotId.length > 0) {
      // A React child replaces this block (BlockSlots.hpp stamps only the ids the
      // host asked for); the host reserves the slot and Fabric mounts the child.
      if (currentTextNodes.count > 0) {
        [segments addObject:[ENRMTextSegment segmentWithNodes:[currentTextNodes copy]]];
        [currentTextNodes removeAllObjects];
      }
      NSString *kindName = child.attributes[@"blockSlotKind"];
      ENRMBlockSlotKind kind = [kindName isEqualToString:@"video"]  ? ENRMBlockSlotKindVideo
                               : [kindName isEqualToString:@"link"] ? ENRMBlockSlotKindLink
                               : [kindName isEqualToString:@"code"] ? ENRMBlockSlotKindCode
                                                                    : ENRMBlockSlotKindImage;
      [segments addObject:[ENRMBlockSlotSegment segmentWithSlotId:blockSlotId kind:kind]];
    } else if (child.type == MarkdownNodeTypeTable) {
      if (currentTextNodes.count > 0) {
        [segments addObject:[ENRMTextSegment segmentWithNodes:[currentTextNodes copy]]];
        [currentTextNodes removeAllObjects];
      }
      [segments addObject:[ENRMTableSegment segmentWithTableNode:child]];
    }
#if ENRICHED_MARKDOWN_MATH
    else if (child.type == MarkdownNodeTypeLatexMathDisplay) {
#if !TARGET_OS_OSX
      if (currentTextNodes.count > 0) {
        [segments addObject:[ENRMTextSegment segmentWithNodes:[currentTextNodes copy]]];
        [currentTextNodes removeAllObjects];
      }
      NSString *latex = child.children.count > 0 ? child.children.firstObject.content : child.content;
      [segments addObject:[ENRMMathSegment segmentWithLatex:latex ?: @""]];
#else
      // TODO: Fix block math rendering on macOS. Adding ENRMMathContainerView as a
      // segment causes all preceding text segments to become invisible.
#endif
    }
#endif
    else if (child.type == MarkdownNodeTypeCodeBlock) {
      if (currentTextNodes.count > 0) {
        [segments addObject:[ENRMTextSegment segmentWithNodes:[currentTextNodes copy]]];
        [currentTextNodes removeAllObjects];
      }
      [segments addObject:[ENRMCodeBlockSegment segmentWithCodeBlockNode:child]];
    } else if (child.type == MarkdownNodeTypeBlockquote || child.type == MarkdownNodeTypeAdmonition) {
      if (currentTextNodes.count > 0) {
        [segments addObject:[ENRMTextSegment segmentWithNodes:[currentTextNodes copy]]];
        [currentTextNodes removeAllObjects];
      }
      [segments addObject:[ENRMBlockquoteSegment segmentWithBlockquoteNode:child]];
    }
#if ENRICHED_MARKDOWN_VIDEO
    else if (child.type == MarkdownNodeTypeVideo) {
      if (currentTextNodes.count > 0) {
        [segments addObject:[ENRMTextSegment segmentWithNodes:[currentTextNodes copy]]];
        [currentTextNodes removeAllObjects];
      }
      [segments addObject:[ENRMVideoSegment segmentWithVideoNode:child]];
    }
#endif
    else {
      [currentTextNodes addObject:child];
    }
  }

  if (currentTextNodes.count > 0) {
    [segments addObject:[ENRMTextSegment segmentWithNodes:currentTextNodes]];
  }

  return segments;
}

static const uint64_t kTextKindSalt = 0x7465787400000000ULL; // "text"

static uint64_t ENRMTextSignature(uint64_t nodesSignature, NSArray<MarkdownASTNode *> *nodes, StyleConfig *config,
                                  NSInteger taskIndexOffset, NSInteger taskItemCount)
{
  uint64_t signature = nodesSignature ^ kTextKindSalt;
  if (config.imageSourceOverrides.count > 0) {
    signature = ENRMMixImageSourceOverrides(signature, nodes, config.imageSourceOverrides);
  }
  if (taskItemCount > 0) {
    signature = ENRMMixTaskIndexOffset(signature, taskIndexOffset);
  }
  return signature;
}

// The text segment for `nodes`, through `cache` when there is one (ENRMTextRenderCache).
static ENRMRenderedSegment *ENRMRenderTextSegment(NSArray<MarkdownASTNode *> *nodes, StyleConfig *config,
                                                  BOOL allowTrailingMargin, BOOL allowFontScaling,
                                                  CGFloat maxFontSizeMultiplier, NSLineBreakStrategy lineBreakStrategy,
                                                  BOOL blockquoteContent, ENRMWritingDirectionMode writingDirectionMode,
                                                  NSWritingDirection layoutDirection, BOOL isLast,
                                                  NSInteger taskIndexOffset, NSInteger taskItemCount,
                                                  ENRMTextRenderCache *cache)
{
  ENRMRenderResult * (^render)(void) = ^ENRMRenderResult *
  {
    return blockquoteContent ? ENRMRenderBlockquoteContentNodes(nodes, config, allowFontScaling, maxFontSizeMultiplier,
                                                                lineBreakStrategy, taskIndexOffset)
                             : ENRMRenderASTNodes(nodes, config, allowTrailingMargin, allowFontScaling,
                                                  maxFontSizeMultiplier, lineBreakStrategy, taskIndexOffset);
  };
  if (!cache) {
    return [ENRMRenderedSegment textSegmentWithResult:render()
                                            signature:ENRMTextSignature(ENRMSignatureForNodes(nodes), nodes, config,
                                                                        taskIndexOffset, taskItemCount)];
  }
  return [cache segmentForNodes:nodes
      isLast:isLast
      signature:^uint64_t(uint64_t nodesSignature) {
        return ENRMTextSignature(nodesSignature, nodes, config, taskIndexOffset, taskItemCount);
      }
      render:^ENRMRenderResult * {
        ENRMRenderResult *result = render();
        ENRMApplyWritingDirectionMode(result.attributedText, writingDirectionMode, layoutDirection);
        return result;
      }
      resume:^ENRMRenderResult *(NSUInteger from, ENRMRenderResult *previous) {
        // The previous render's task indices hold only if it started from the same one.
        if (previous.blockTaskStarts.firstObject.integerValue != taskIndexOffset) {
          return nil;
        }
        return ENRMRenderASTNodesResuming(nodes, from, previous, config, allowTrailingMargin, allowFontScaling,
                                          maxFontSizeMultiplier, lineBreakStrategy, writingDirectionMode,
                                          layoutDirection);
      }];
}

static NSArray<ENRMRenderedSegment *> *
ENRMRenderSegments(MarkdownASTNode *ast, StyleConfig *config, BOOL allowTrailingMargin, BOOL allowFontScaling,
                   CGFloat maxFontSizeMultiplier, NSLineBreakStrategy lineBreakStrategy, BOOL blockquoteContent,
                   NSInteger startingTaskIndex, ENRMWritingDirectionMode writingDirectionMode,
                   NSWritingDirection layoutDirection, ENRMTextRenderCache *cache)
{
  NSArray *segments = ENRMSplitASTIntoSegments(ast);
  NSUInteger lastTextIndex = [segments indexOfObjectWithOptions:NSEnumerationReverse
                                                    passingTest:^BOOL(id segment, NSUInteger index, BOOL *stop) {
                                                      return [segment isKindOfClass:[ENRMTextSegment class]];
                                                    }];
  NSMutableArray<ENRMRenderedSegment *> *renderedSegments = [NSMutableArray array];
  NSInteger taskIndexOffset = startingTaskIndex;
  NSMutableDictionary<NSString *, ENRMLiftedIndent *> *liftedIndents = [NSMutableDictionary dictionary];

  static const uint64_t kTableKindSalt = 0x7461626C00000000ULL;      // "tabl"
  static const uint64_t kMathKindSalt = 0x6D61746800000000ULL;       // "math"
  static const uint64_t kCodeBlockKindSalt = 0x63626C6B00000000ULL;  // "cblk"
  static const uint64_t kBlockquoteKindSalt = 0x6271746500000000ULL; // "bqte"
  static const uint64_t kVideoKindSalt = 0x7669646F00000000ULL;      // "vido"
  static const uint64_t kBlockSlotKindSalt = 0x626C736C00000000ULL;  // "blsl"
  NSDictionary<NSString *, ENRMImageSourceOverride *> *imageSourceOverrides = config.imageSourceOverrides;

  for (NSUInteger segmentIndex = 0; segmentIndex < segments.count; segmentIndex++) {
    id segment = segments[segmentIndex];
    if ([segment isKindOfClass:[ENRMTextSegment class]]) {
      NSArray<MarkdownASTNode *> *nodes = ((ENRMTextSegment *)segment).nodes;
      NSInteger taskItemCount = 0;
      for (MarkdownASTNode *node in nodes) {
        taskItemCount += ENRMCountTaskItems(node);
      }
      ENRMRenderedSegment *rendered =
          ENRMRenderTextSegment(nodes, config, allowTrailingMargin, allowFontScaling, maxFontSizeMultiplier,
                                lineBreakStrategy, blockquoteContent, writingDirectionMode, layoutDirection,
                                segmentIndex == lastTextIndex, taskIndexOffset, taskItemCount, cache);
      [renderedSegments addObject:rendered];
      taskIndexOffset += taskItemCount;
      [liftedIndents addEntriesFromDictionary:rendered.textResult.context.liftedIndents];
    } else if ([segment isKindOfClass:[ENRMTableSegment class]]) {
      ENRMTableSegment *tableSegment = (ENRMTableSegment *)segment;
      uint64_t signature = ENRMSignatureForNode(tableSegment.tableNode) ^ kTableKindSalt;
      if (tableSegment.liftKey) {
        ENRMLiftedIndent *lift = liftedIndents[tableSegment.liftKey];
        tableSegment.lift = lift;
        signature = ENRMMixTaskIndexOffset(signature, (NSInteger)lround(lift.indent * 64));
        signature = ENRMMixTaskIndexOffset(signature, lift.firstStrongDirection * 4 + lift.wordMajorityDirection + 8);
      }
      if (imageSourceOverrides.count > 0) {
        signature = ENRMMixImageSourceOverrides(signature, @[ tableSegment.tableNode ], imageSourceOverrides);
      }
      [renderedSegments addObject:[ENRMRenderedSegment tableSegmentWithSegment:tableSegment signature:signature]];
    }
#if ENRICHED_MARKDOWN_MATH
    else if ([segment isKindOfClass:[ENRMMathSegment class]]) {
      ENRMMathSegment *mathSegment = (ENRMMathSegment *)segment;
      uint64_t signature = ENRMSignatureForNode(nil) ^ kMathKindSalt;
      NSString *latex = mathSegment.latex ?: @"";
      const char *utf8 = [latex UTF8String];
      while (*utf8) {
        signature ^= (uint8_t)*utf8++;
        signature *= 1099511628211ULL;
      }
      [renderedSegments addObject:[ENRMRenderedSegment mathSegmentWithSegment:mathSegment signature:signature]];
    }
#endif
    else if ([segment isKindOfClass:[ENRMCodeBlockSegment class]]) {
      ENRMCodeBlockSegment *codeBlockSegment = (ENRMCodeBlockSegment *)segment;
      uint64_t signature = ENRMSignatureForNode(codeBlockSegment.codeBlockNode) ^ kCodeBlockKindSalt;
      [renderedSegments addObject:[ENRMRenderedSegment codeBlockSegmentWithSegment:codeBlockSegment
                                                                         signature:signature]];
    } else if ([segment isKindOfClass:[ENRMBlockquoteSegment class]]) {
      ENRMBlockquoteSegment *blockquoteSegment = (ENRMBlockquoteSegment *)segment;
      uint64_t signature = ENRMSignatureForNode(blockquoteSegment.blockquoteNode) ^ kBlockquoteKindSalt;
      if (imageSourceOverrides.count > 0) {
        signature = ENRMMixImageSourceOverrides(signature, @[ blockquoteSegment.blockquoteNode ], imageSourceOverrides);
      }
      NSInteger taskItemCount = ENRMCountTaskItems(blockquoteSegment.blockquoteNode);
      if (taskItemCount > 0) {
        signature = ENRMMixTaskIndexOffset(signature, taskIndexOffset);
      }
      blockquoteSegment.taskIndexOffset = taskIndexOffset;
      taskIndexOffset += taskItemCount;
      [renderedSegments addObject:[ENRMRenderedSegment blockquoteSegmentWithSegment:blockquoteSegment
                                                                          signature:signature]];
    }
#if ENRICHED_MARKDOWN_VIDEO
    else if ([segment isKindOfClass:[ENRMVideoSegment class]]) {
      ENRMVideoSegment *videoSegment = (ENRMVideoSegment *)segment;
      uint64_t signature = ENRMSignatureForNode(videoSegment.videoNode) ^ kVideoKindSalt;
      [renderedSegments addObject:[ENRMRenderedSegment videoSegmentWithSegment:videoSegment signature:signature]];
    }
#endif
    else if ([segment isKindOfClass:[ENRMBlockSlotSegment class]]) {
      ENRMBlockSlotSegment *blockSlotSegment = (ENRMBlockSlotSegment *)segment;
      uint64_t signature = kBlockSlotKindSalt;
      const char *utf8 = [blockSlotSegment.slotId UTF8String];
      while (utf8 && *utf8) {
        signature ^= (uint8_t)*utf8++;
        signature *= 1099511628211ULL;
      }
      [renderedSegments addObject:[ENRMRenderedSegment blockSlotSegmentWithSegment:blockSlotSegment
                                                                         signature:signature]];
    }
  }

  return renderedSegments;
}

NSArray<ENRMRenderedSegment *> *ENRMRenderSegmentsFromAST(MarkdownASTNode *ast, StyleConfig *config,
                                                          BOOL allowTrailingMargin, BOOL allowFontScaling,
                                                          CGFloat maxFontSizeMultiplier,
                                                          NSLineBreakStrategy lineBreakStrategy, BOOL blockquoteContent,
                                                          NSInteger startingTaskIndex)
{
  return ENRMRenderSegments(ast, config, allowTrailingMargin, allowFontScaling, maxFontSizeMultiplier,
                            lineBreakStrategy, blockquoteContent, startingTaskIndex,
                            ENRMWritingDirectionModeFirstStrong, NSWritingDirectionNatural, /*cache*/ nil);
}

NSArray<ENRMRenderedSegment *> *
ENRMRenderSegmentsFromASTCached(MarkdownASTNode *ast, StyleConfig *config, BOOL allowTrailingMargin,
                                BOOL allowFontScaling, CGFloat maxFontSizeMultiplier,
                                NSLineBreakStrategy lineBreakStrategy, ENRMWritingDirectionMode writingDirectionMode,
                                NSWritingDirection layoutDirection, ENRMTextRenderCache *cache)
{
  return ENRMRenderSegments(ast, config, allowTrailingMargin, allowFontScaling, maxFontSizeMultiplier,
                            lineBreakStrategy, /*blockquoteContent*/ NO, /*startingTaskIndex*/ 0, writingDirectionMode,
                            layoutDirection, cache);
}

ENRMRenderResult *ENRMRenderTextCached(NSArray<MarkdownASTNode *> *nodes, StyleConfig *config, BOOL allowTrailingMargin,
                                       BOOL allowFontScaling, CGFloat maxFontSizeMultiplier,
                                       NSLineBreakStrategy lineBreakStrategy,
                                       ENRMWritingDirectionMode writingDirectionMode,
                                       NSWritingDirection layoutDirection, ENRMTextRenderCache *cache)
{
  return ENRMRenderTextSegment(nodes, config, allowTrailingMargin, allowFontScaling, maxFontSizeMultiplier,
                               lineBreakStrategy, /*blockquoteContent*/ NO, writingDirectionMode, layoutDirection,
                               /*isLast*/ YES, /*taskIndexOffset*/ 0, /*taskItemCount*/ 0, cache)
      .textResult;
}
