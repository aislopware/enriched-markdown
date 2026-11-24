import { normalizeMarkdownStyle } from '../../normalizeMarkdownStyle.web';
import { buildStyles, tableBodyRowStyle } from '../styles';

describe("table.lines: 'rows'", () => {
  const style = normalizeMarkdownStyle({
    table: {
      lines: 'rows',
      borderWidth: 1,
      borderColor: '#ccc',
      borderRadius: 8,
    },
  });
  const styles = buildStyles(style);

  it('draws no outer box', () => {
    expect(styles.table.border).toBe('none');
    expect(styles.tableWrapper.borderRadius).toBe(0);
    expect(styles.tableCell.default.border).toBe('none');
    expect(styles.tableCell.default.borderBottom).toBeUndefined();
  });

  it('draws a line above every body row, so none under the last or a lone header', () => {
    expect(styles.tableHeaderCell.default.border).toBe('none');
    expect(styles.tableHeaderCell.default.borderBottom).toBeUndefined();
    expect(tableBodyRowStyle(style, 0).borderTop).toMatch(/^1px solid /);
    expect(tableBodyRowStyle(style, 0).borderBottom).toBeUndefined();
  });
});
