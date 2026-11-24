#import "BaseRenderer.h"
#import "RenderContext.h"

extern NSString *const ListDepthAttribute;
extern NSString *const ListTypeAttribute;
extern NSString *const ListItemNumberAttribute;
extern NSString *const ListItemMarkerStartAttribute;

extern NSString *const TaskItemAttribute;
extern NSString *const TaskCheckedAttribute;
extern NSString *const TaskIndexAttribute;

// Set on a paragraph of a continuation item: it carries on its item's text without a marker.
extern NSString *const ListItemContinuationAttribute;
// Set on an item a table was lifted out of: its text goes on in other segments, which an in-place
// task toggle would not restyle.
extern NSString *const ListItemLiftedAttribute;

// Node attributes SegmentRenderer sets on the list copies it splits around a table in a list item.
extern NSString *const ENRMLiftKeyNodeAttribute;
extern NSString *const ENRMListContinuationNodeAttribute;
extern NSString *const ENRMListNoMarginTopNodeAttribute;
extern NSString *const ENRMListNoMarginBottomNodeAttribute;
extern NSString *const ENRMListMarkerNumberNodeAttribute;

@interface ENRMListMarkerDescriptor : NSObject
@property (nonatomic, assign) BOOL isTask;
@property (nonatomic, assign) BOOL isChecked;
@property (nonatomic, assign) NSInteger taskIndex;
@property (nonatomic, assign) ListType listType;
@property (nonatomic, assign) NSInteger number;
@property (nonatomic, assign) NSInteger depth;
@property (nonatomic, assign) CGFloat indent;
@end

@interface ListItemRenderer : BaseRenderer
@end
