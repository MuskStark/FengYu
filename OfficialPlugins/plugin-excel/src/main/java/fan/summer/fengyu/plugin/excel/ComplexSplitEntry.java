package fan.summer.fengyu.plugin.excel;

/**
 * One COMPLEX-mode split rule, held in memory on the session's {@link SplitConfig}
 * (replaces the 3.2.0 DB-backed {@code ComplexSplitConfigEntity}).
 *
 * @param fieldName    original source filename (informational)
 * @param sheetName    sheet this rule applies to
 * @param headerIndex  1-based header row; {@code -1} with columnIndex {@code -1} = copy entire sheet
 * @param columnIndex  1-based column to split by; {@code -1} with headerIndex {@code -1} = copy entire sheet
 */
public record ComplexSplitEntry(String fieldName, String sheetName, int headerIndex, int columnIndex) {}
