import { act } from 'react';
import { createRoot } from 'test-renderer';
import type { Root } from 'test-renderer';
import { EnrichedMarkdownText } from '../src/native/EnrichedMarkdownText';
import type { TextSelectionMenuConfig } from '../src';

jest.mock('../src/EnrichedMarkdownNativeComponent', () => ({
  __esModule: true,
  default: 'EnrichedMarkdownNativeComponent',
}));

jest.mock('../src/EnrichedMarkdownTextNativeComponent', () => ({
  __esModule: true,
  default: 'EnrichedMarkdownTextNativeComponent',
}));

function nativeSelectionMenuConfig(config: TextSelectionMenuConfig) {
  const root: Root = createRoot({
    textComponentTypes: [
      'EnrichedMarkdownNativeComponent',
      'EnrichedMarkdownTextNativeComponent',
    ],
  });
  act(() =>
    root.render(
      <EnrichedMarkdownText markdown="text" selectionMenuConfig={config} />
    )
  );
  const [nativeView] = root.container.queryAll((instance) =>
    String(instance.type).startsWith('EnrichedMarkdown')
  );
  if (!nativeView) throw new Error('Native markdown view was not rendered');
  return nativeView.props.selectionMenuConfig;
}

it.each([
  // Only `other` matches native's fallback, so no per-count table is sent.
  [{ other: 'Copy {count} URLs' }, 0],
  [{ other: 'Copy {count} URLs', few: 'Copy {count} URLs (few)' }, 101],
])('sends %j as %i plural templates', (pluralLabels, count) => {
  const config = nativeSelectionMenuConfig({
    copyImageUrl: { label: 'Copy URL', pluralLabels },
  });

  expect(config.copyImageUrlsLabel).toBe('Copy {count} URLs');
  expect(config.copyImageUrlPluralTemplates).toHaveLength(count);
});
