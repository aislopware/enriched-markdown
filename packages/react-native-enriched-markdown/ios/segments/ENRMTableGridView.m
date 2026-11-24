#import "ENRMTableGridView.h"
#import "ENRMMenuAction.h"

#if TARGET_OS_OSX

@implementation ENRMMacOSTableRowData
@end

@implementation ENRMTableGridView {
  NSArray<ENRMMacOSTableRowData *> *_tableRows;
  NSArray<NSNumber *> *_columnWidths;
  NSArray<NSNumber *> *_rowHeights;
  NSColor *_borderColor;
  CGFloat _borderWidth;
  CGFloat _horizontalCellPadding;
  CGFloat _verticalCellPadding;
  CGFloat _cornerRadius;
}

- (BOOL)isFlipped
{
  return YES;
}

- (void)updateWithRows:(NSArray<ENRMMacOSTableRowData *> *)rows
             columnWidths:(NSArray<NSNumber *> *)columnWidths
               rowHeights:(NSArray<NSNumber *> *)rowHeights
              borderColor:(NSColor *)borderColor
              borderWidth:(CGFloat)borderWidth
    horizontalCellPadding:(CGFloat)horizontalCellPadding
      verticalCellPadding:(CGFloat)verticalCellPadding
             cornerRadius:(CGFloat)cornerRadius
{
  _tableRows = rows;
  _columnWidths = columnWidths;
  _rowHeights = rowHeights;
  _borderColor = borderColor;
  _borderWidth = borderWidth;
  _horizontalCellPadding = horizontalCellPadding;
  _verticalCellPadding = verticalCellPadding;
  _cornerRadius = cornerRadius;
  [self setNeedsDisplay:YES];
}

- (NSMenu *)menuForEvent:(NSEvent *)event
{
  return _menuProvider ? _menuProvider() : [super menuForEvent:event];
}

- (void)drawRect:(NSRect)dirtyRect
{
  if (!_tableRows.count || !_columnWidths.count || !_rowHeights.count)
    return;

  [[NSGraphicsContext currentContext] saveGraphicsState];

  NSBezierPath *outline = nil;
  if (_cornerRadius > 0 && !_rowLinesOnly) {
    outline = [NSBezierPath bezierPathWithRoundedRect:self.bounds xRadius:_cornerRadius yRadius:_cornerRadius];
    [outline setClip];
  }

  CGFloat yOffset = 0;
  for (NSUInteger r = 0; r < _tableRows.count; r++) {
    ENRMMacOSTableRowData *rowData = _tableRows[r];
    CGFloat rowHeight = [_rowHeights[r] doubleValue];
    CGFloat xOffset = 0;

    for (NSUInteger c = 0; c < rowData.cellTexts.count; c++) {
      CGFloat columnWidth = [_columnWidths[c] doubleValue];
      CGFloat cellOutline = _rowLinesOnly ? 0 : _borderWidth;
      NSRect cellRect = NSMakeRect(xOffset, yOffset, columnWidth + cellOutline, rowHeight + cellOutline);

      [rowData.backgroundColor setFill];
      NSRectFill(cellRect);

      if (!_rowLinesOnly) {
        [_borderColor setStroke];
        NSBezierPath *border = [NSBezierPath bezierPathWithRect:cellRect];
        border.lineWidth = _borderWidth;
        [border stroke];
      }

      NSAttributedString *text = rowData.cellTexts[c];
      if (text.length > 0) {
        CGFloat leftPadding = (_rowLinesOnly && c == 0) ? 0 : _horizontalCellPadding;
        CGFloat rightPadding = (_rowLinesOnly && c + 1 == _columnWidths.count) ? 0 : _horizontalCellPadding;
        NSRect textRect = NSMakeRect(xOffset + leftPadding, yOffset + _verticalCellPadding,
                                     columnWidth - leftPadding - rightPadding, rowHeight - _verticalCellPadding * 2);
        [text drawWithRect:textRect
                   options:NSStringDrawingUsesLineFragmentOrigin | NSStringDrawingUsesFontLeading
                   context:nil];
      }

      xOffset += columnWidth;
    }
    if (_rowLinesOnly && _borderWidth > 0 && r + 1 < _tableRows.count) {
      [_borderColor setFill];
      NSRectFill(NSMakeRect(0, yOffset + rowHeight - _borderWidth, xOffset, _borderWidth));
    }
    yOffset += rowHeight;
  }

  // The rounded clip cuts the square cell strokes; draw the arcs.
  if (outline && _borderWidth > 0) {
    [_borderColor setStroke];
    outline.lineWidth = _borderWidth;
    [outline stroke];
  }

  [[NSGraphicsContext currentContext] restoreGraphicsState];
}

@end

#endif // TARGET_OS_OSX
