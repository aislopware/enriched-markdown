export interface LinkPressEvent {
  url: string;
}

export interface LinkLongPressEvent {
  url: string;
}

export interface ImagePressEvent {
  url: string;
  altText: string;
}

export interface TextPressEvent {
  /** UTF-16 index of the tapped character within `text`. */
  index: number;
  /** The rendered text of the tapped block. */
  text: string;
}

export interface TaskListItemPressEvent {
  index: number;
  checked: boolean;
  text: string;
}

export interface CopyPressEvent {
  code: string;
  language: string;
}

export interface CodeBlockPressEvent {
  code: string;
  language: string;
}

/**
 * Event payload fired when a text selection is copied from the native
 * selection menu, with the keyboard shortcut, or through TalkBack, and when a
 * table or quote is copied whole from its long-press menu.
 */
export interface SelectionCopyEvent {
  /** What was put on the clipboard as plain text. */
  text: string;
  /**
   * How it was copied: `'plain'` for Copy and for keyboard or TalkBack copies,
   * `'markdown'` for Copy as Markdown, `'imageUrls'` for Copy Image URL(s).
   */
  format: 'plain' | 'markdown' | 'imageUrls';
  /**
   * The copied range, in UTF-16 offsets of the rendered text. With
   * `flavor="github"` the offsets restart at each block that renders as its own
   * view, such as a table, code block or quote, so consecutive paragraphs count
   * as one run of text. A table's or quote's long-press copy reports the whole
   * block: `start` is 0 and `end` is the length of the block's text with links
   * as their labels (what Copy writes with `copyLinksAs: 'label'`), so its Copy
   * and Copy as Markdown report the same range.
   */
  selection: { start: number; end: number };
  /**
   * The copied selection, or the copied table or quote, as Markdown, whatever
   * the format: links keep their URLs here even when `text` has only their
   * labels.
   */
  markdown: string;
}

/**
 * Event payload fired when a math expression fails to parse or render.
 *
 * The whole expression is the unit of failure: the LaTeX engine either renders
 * an expression in full or rejects it, so there is no single offending command
 * to report. Consumers can classify `source` themselves (e.g. report it to an
 * error tracker) without maintaining an allowlist that goes stale on engine
 * upgrades.
 */
export interface LatexErrorEvent {
  /** Raw LaTeX of the failing expression, without `$`/`$$` delimiters. */
  source: string;
  /** Engine error message, when one is available. */
  message?: string;
  /** `false` for inline `$...$`, `true` for block `$$...$$`. */
  displayMode: boolean;
}

/** A link menu item as sent to native: no callback, every field set. */
export interface LinkContextMenuItemConfig {
  text: string;
  icon: string;
  disabled: boolean;
  destructive: boolean;
}

/** The menu items for links whose URL matches `pattern`. */
export interface LinkContextMenuItemsConfig {
  pattern: string;
  items: ReadonlyArray<Readonly<LinkContextMenuItemConfig>>;
}

/** `pattern` is the entry that supplied the menu, so JS finds the item without matching again. */
export interface OnLinkContextMenuItemPressEvent {
  url: string;
  pattern: string;
  itemText: string;
}

/**
 * Native-level context menu item config sent to the native component.
 * Does not include the `onPress` callback — callbacks are managed on the JS side.
 */
export interface ContextMenuItemConfig {
  text: string;
  icon?: string;
}

/**
 * Event payload fired by the native component when a context menu item is pressed.
 */
export interface OnContextMenuItemPressEvent {
  itemText: string;
  selectedText: string;
  selectionStart: number;
  selectionEnd: number;
}
