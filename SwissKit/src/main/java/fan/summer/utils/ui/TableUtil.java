package fan.summer.utils.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.util.List;

/**
 * 类的详细说明
 *
 * @author summer
 * @version 1.00
 * @Date 2026/3/18
 */
public abstract class TableUtil {
    public static JTable initTable(JTable table, String[] columns, List<Object[]> rowData, int isCellEditableIndex) {
        DefaultTableModel defaultTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column > isCellEditableIndex;
            }
        };
        for (Object[] row : rowData) {
            defaultTableModel.addRow(row);
        }
        table.setModel(defaultTableModel);
        return table;
    }

}
