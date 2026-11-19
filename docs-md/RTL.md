# RTL Support

`react-native-enriched-markdown` resolves writing direction **per paragraph** on iOS, Android and web: each paragraph picks its own base direction from its first strong directional character. Arabic, Hebrew, and Persian content right-aligns automatically, even inside an LTR app and even when mixed with English paragraphs in the same document.

This document describes the read-only [`EnrichedMarkdownText`](TEXT.md) renderer. The rich [`EnrichedMarkdownTextInput`](INPUT.md#rtl-support) follows the same per-paragraph rules — see its [RTL section](INPUT.md#rtl-support) for input-specific caveats (placeholder, code blocks, etc.).

## Platform Setup

No setup is required. Every platform autodetects direction per paragraph out of the box.

### Android

Uses Android's `TEXT_DIRECTION_FIRST_STRONG` heuristic on every `StaticLayout`. Paragraphs with no strong character fall back to the view's resolved layout direction (inherits ancestor `<View style={{ direction: 'rtl' }}>` and `I18nManager.isRTL`).

### iOS

iOS TextKit's `NSWritingDirectionNatural` does **not** do per-paragraph first-strong — it follows the app's global UI layout direction. The library implements first-strong itself as a post-render pass, matching Android's behavior. The mode is controlled by the [`writingDirection`](#writingdirection-prop-ios) prop and defaults to `'first-strong'`.

> **Note:** Earlier versions documented `I18nManager.forceRTL(true)` as a requirement on iOS. That is no longer needed for content direction — `first-strong` resolves each paragraph from its content. `I18nManager.forceRTL` still affects the surrounding app layout (Yoga direction, navigation, etc.) and remains useful if you want the whole app in RTL mode, but it's no longer a precondition for Markdown to render right-aligned.

### Web

Each top-level block gets `dir="auto"`, so the browser resolves it from its first strong character, and CSS logical properties put blockquote borders, list markers and unaligned table cells on the matching side. `EnrichedMarkdownTextInput` does the same per line. A list takes its direction from its first item. Pass `dir="ltr"` or `dir="rtl"` to force one direction for the whole document; `writingDirection` is ignored on web.

## `writingDirection` prop (iOS)

| Value | Behavior |
|---|---|
| `'first-strong'` (default) | Per-paragraph autodetection. Neutral-only paragraphs fall back to the view's resolved layout direction. Matches Android. |
| `'auto'` | React Native parity. TextKit follows the app's `userInterfaceLayoutDirection`; mixed-direction documents do not auto-resolve. |
| `'ltr'` | Forces LTR on every paragraph. |
| `'rtl'` | Forces RTL on every paragraph. |
| `'word-majority'` | Per-paragraph, by the direction most words read in. See [below](#word-majority). Also honored on Android. |

Code blocks always render LTR regardless of this prop.

Android ignores the other values; it always uses first-strong via the platform.

### Word majority

First-strong reads an Arabic or Hebrew paragraph that opens with an English word, an inline code span or a bare URL as left-to-right. `'word-majority'` resolves each paragraph (list item, heading, table cell) from its words instead:

- A word takes the direction of its first letter; words without letters (numbers, punctuation, emoji) don't count.
- Each sentence follows most of its words, and the paragraph follows most of its sentences, so one long English sentence cannot outvote the Arabic ones around it. Sentences end at `.`, `!`, `?` or `؟` followed by whitespace (not the dot in "e.g." or "i.e.").
- A tie between sentences goes to the paragraph's word count, then to its first word.
- Inline code doesn't count: it reads as whitespace, so a command quoted in Arabic prose can't outvote it. Link text and URLs count like any other words.
- Paragraphs without letters fall back to the layout direction, as with `'first-strong'`. Directional marks (LRM, RLM) are not read.

These are the counting rules of Onicode's desktop client, which feeds them each top-level block (a whole list or quote) rather than each paragraph. The native renderers keep the paragraph as the unit, so a list item or a line inside a quote can go the other way from its neighbours.

On Android it needs Android 10 (API 29); older versions keep first-strong. Web ignores the prop.

```tsx
<EnrichedMarkdownText writingDirection="word-majority" markdown={reply} />
```

```tsx
// Default — mixed Arabic/English document renders each paragraph correctly.
<EnrichedMarkdownText markdown={mixedContent} />

// Force RTL for the whole document (e.g. forms or admin UI in an Arabic locale).
<EnrichedMarkdownText writingDirection="rtl" markdown={content} />
```

## Element RTL Behavior

Each element follows the **paragraph it belongs to**, not a global flag — so a single document can mix sides cleanly.

| Element | Behavior |
|---|---|
| **Paragraphs & headings** | Base direction set per paragraph from first-strong (or forced by the prop). |
| **Unordered lists** | Bullet drawn on the side that matches the item's paragraph direction. |
| **Ordered lists** | Number drawn on the side that matches the item's paragraph direction. |
| **Task lists** | Checkbox drawn on the matching side; tap hit-test follows the same side. |
| **Blockquotes** | Border drawn on the side that matches the quoted paragraph. |
| **Tables** (`flavor="github"`) | Each cell resolves its own direction independently from cell content. On web, column order follows the container. |
| **Code blocks** | Always LTR. |
| **Inline code** | Inherits its paragraph's direction; characters flow correctly via TextKit Bidi. |

## Yoga layout-direction inheritance

For paragraphs with no strong directional character (digits, punctuation, block spacers), the library falls back to the view's **Yoga-resolved layout direction**. That means:

```tsx
// "123 456 789." has no strong character. Inside an RTL ancestor view,
// it right-aligns; otherwise it left-aligns.
<View style={{ direction: 'rtl' }}>
  <EnrichedMarkdownText markdown="123 456 789." />
</View>
```

On web, such a block gets no `dir` and inherits the container's direction.

This mirrors Android's first-strong fallback and lets you place an LTR app in an RTL screen (or vice versa) without surprises for neutral content.

## Copy-as-HTML caveat

When you copy markdown content to the clipboard, the HTML representation carries a single `dir` attribute on `<html>` (or on `<table>` for table copies). On iOS and macOS that attribute is read from the document's first paragraph:

- `first-strong` document starting with Arabic → `<html dir="rtl">`
- `first-strong` document starting with English → `<html dir="ltr">`
- `'auto'` → `<html dir="auto">` (receivers do best-effort first-strong)
- `'ltr'` / `'rtl'` → forced

On Android the HTML carries `dir="rtl"` only when the app's layout direction is RTL; otherwise no `dir` is set, and table copies carry none.

**Receivers (Gmail, Notes, Word, etc.) apply their own Bidi algorithm to the pasted HTML.** Mixed-direction documents may not visually reproduce the per-paragraph layout you see in-app — receivers can only honor what the markup encodes, and HTML's `dir` attribute is scoped to elements, not paragraphs within them. The plain-text and Markdown clipboard representations are unaffected and round-trip cleanly.

If you need precise mixed-direction output in a specific receiver, the rendered HTML carries no per-`<p>` `dir` attribute today — that would be a future enhancement.
