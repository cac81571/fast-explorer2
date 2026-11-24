package com.fastexplorer.ui;

import com.fastexplorer.Messages;

import javax.swing.table.AbstractTableModel;
import java.util.List;

public final class FileTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            Messages.COL_NAME,
            Messages.COL_LOCATION,
            Messages.COL_KIND,
            Messages.COL_SIZE,
            Messages.COL_MODIFIED
    };

    private List<FileRow> rows = List.of();

    public void setRows(List<FileRow> rows) {
        this.rows = List.copyOf(rows);
        fireTableDataChanged();
    }

    public FileRow rowAt(int modelRow) {
        return rows.get(modelRow);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return switch (column) {
            case 0 -> FileRow.class;
            case 3, 4 -> Long.class;
            default -> String.class;
        };
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return false;
    }

    @Override
    public Object getValueAt(int row, int column) {
        FileRow entry = rows.get(row);
        return switch (column) {
            case 0 -> entry;
            case 1 -> entry.location();
            case 2 -> Formatters.kind(entry.kind());
            case 3 -> entry.size();
            case 4 -> entry.modifiedEpochMillis();
            default -> "";
        };
    }
}
