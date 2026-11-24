package com.fastexplorer.ui;

import com.fastexplorer.index.IndexRecord;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Table of folders that have been walked into the search index. */
public final class IndexPanel extends JPanel {
    private final IndexTableModel model = new IndexTableModel();
    private final JTable table = new JTable(model);
    private final JTextField filter = new JTextField();
    private final Consumer<IndexRecord> open;
    private final Consumer<IndexRecord> reindex;
    private final Consumer<IndexRecord> cancel;
    private final Consumer<IndexRecord> delete;
    private List<IndexRecord> all = List.of();

    public IndexPanel(
            Consumer<IndexRecord> open,
            Consumer<IndexRecord> reindex,
            Consumer<IndexRecord> cancel,
            Consumer<IndexRecord> delete
    ) {
        super(new BorderLayout(0, 6));
        this.open = open;
        this.reindex = reindex;
        this.cancel = cancel;
        this.delete = delete;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(toolbar(), BorderLayout.NORTH);
        table.setFillsViewportHeight(true);
        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setName("indexTable");
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        if (table.getColumnModel().getColumnCount() > 0) {
            table.getColumnModel().getColumn(0).setPreferredWidth(420);
        }
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
                    IndexRecord record = selected();
                    if (record != null) {
                        open.accept(record);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event);
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void setRecords(List<IndexRecord> records) {
        String selected = selectedKey();
        all = List.copyOf(records);
        applyFilter();
        if (selected != null) {
            for (int i = 0; i < model.getRowCount(); i++) {
                if (selected.equals(model.row(i).rootKey())) {
                    table.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }
    }

    private JPanel toolbar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "パスで絞り込み");
        filter.setName("indexFilter");
        filter.setMaximumSize(new Dimension(280, filter.getPreferredSize().height));
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                applyFilter();
            }
        });
        bar.add(filter);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(button("開く", () -> act(open)));
        bar.add(Box.createHorizontalStrut(4));
        bar.add(button("再インデックス", () -> act(reindex)));
        bar.add(Box.createHorizontalStrut(4));
        bar.add(button("中止", () -> act(cancel)));
        bar.add(Box.createHorizontalStrut(4));
        bar.add(button("削除", () -> act(delete)));
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    private void applyFilter() {
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        List<IndexRecord> shown = new ArrayList<>();
        for (IndexRecord record : all) {
            if (query.isEmpty()
                    || record.displayPath().toLowerCase(Locale.ROOT).contains(query)
                    || record.rootKey().toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(record);
            }
        }
        model.setRows(shown);
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(event.getPoint());
        if (row >= 0) {
            table.setRowSelectionInterval(row, row);
        }
        IndexRecord record = selected();
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("開く", record != null, () -> open.accept(record)));
        menu.add(item("再インデックス", record != null, () -> reindex.accept(record)));
        menu.add(item("中止", record != null && record.status() == IndexRecord.Status.RUNNING, () -> cancel.accept(record)));
        menu.add(item("インデックスを削除", record != null, () -> delete.accept(record)));
        menu.show(table, event.getX(), event.getY());
    }

    private void act(Consumer<IndexRecord> action) {
        IndexRecord record = selected();
        if (record != null) {
            action.accept(record);
        }
    }

    private IndexRecord selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= model.getRowCount()) {
            return null;
        }
        return model.row(row);
    }

    private String selectedKey() {
        IndexRecord record = selected();
        return record == null ? null : record.rootKey();
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static javax.swing.JMenuItem item(String text, boolean enabled, Runnable action) {
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(text);
        item.setEnabled(enabled);
        item.addActionListener(event -> action.run());
        return item;
    }

    private static final class IndexTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"パス", "状態", "最終インデックス", "フォルダ数", "ファイル数", "所要時間"};
        private List<IndexRecord> rows = List.of();

        void setRows(List<IndexRecord> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        IndexRecord row(int index) {
            return rows.get(index);
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
        public Object getValueAt(int row, int column) {
            IndexRecord record = rows.get(row);
            return switch (column) {
                case 0 -> record.displayPath();
                case 1 -> statusText(record.status());
                case 2 -> record.finishedAt() > 0 ? Formatters.time(record.finishedAt()) : Formatters.time(record.startedAt());
                case 3 -> record.folderCount();
                case 4 -> record.fileCount();
                case 5 -> duration(record.durationMs());
                default -> "";
            };
        }

        private static String statusText(IndexRecord.Status status) {
            return switch (status) {
                case RUNNING -> "実行中";
                case DONE -> "完了";
                case CANCELLED -> "中断";
                case ERROR -> "エラー";
            };
        }

        private static String duration(long millis) {
            if (millis < 1000) {
                return millis + " ms";
            }
            return String.format(Locale.US, "%.1f 秒", millis / 1000.0);
        }
    }
}
