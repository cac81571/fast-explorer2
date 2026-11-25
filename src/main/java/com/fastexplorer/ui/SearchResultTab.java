package com.fastexplorer.ui;

import com.fastexplorer.Messages;
import com.fastexplorer.model.SearchHit;
import com.fastexplorer.search.ExtensionFilter;
import com.fastexplorer.util.ListedPaths;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One closable search-results tab and its hit list. */
public final class SearchResultTab {
    static final String CLIENT_KEY = "fast-explorer.searchTab";

    private final FileTableModel model;
    private final JTable table;
    private final JScrollPane scroll;
    private final List<SearchHit> hits = new ArrayList<>();
    private final String title;

    private ExtensionFilter filter = ExtensionFilter.all();
    private long elapsed;
    private boolean finished;
    private boolean dirty;
    private int scanned;
    private int errors;

    SearchResultTab(FileTableModel model, JTable table, JScrollPane scroll, String title) {
        this.model = model;
        this.table = table;
        this.scroll = scroll;
        this.title = title;
        scroll.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        scroll.putClientProperty(CLIENT_KEY, this);
        table.putClientProperty(CLIENT_KEY, this);
    }

    public FileTableModel model() {
        return model;
    }

    public JTable table() {
        return table;
    }

    public JScrollPane scroll() {
        return scroll;
    }

    public String title() {
        return title;
    }

    public List<SearchHit> hits() {
        return hits;
    }

    public ExtensionFilter filter() {
        return filter;
    }

    public void setFilter(ExtensionFilter filter) {
        this.filter = filter == null ? ExtensionFilter.all() : filter;
    }

    public long elapsed() {
        return elapsed;
    }

    public boolean finished() {
        return finished;
    }

    public boolean dirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public int scanned() {
        return scanned;
    }

    public int errors() {
        return errors;
    }

    public void reset(ExtensionFilter filter) {
        this.filter = filter == null ? ExtensionFilter.all() : filter;
        hits.clear();
        model.setRows(List.of());
        elapsed = 0;
        finished = false;
        dirty = false;
        scanned = 0;
        errors = 0;
    }

    public void replaceHits(List<SearchHit> cacheHits, long elapsedMillis, int cap) {
        hits.clear();
        hits.addAll(cacheHits);
        capHits(cap);
        elapsed = elapsedMillis;
        finished = false;
        dirty = true;
    }

    public void updateDirectory(String dirKey, List<SearchHit> matches, long elapsedMillis, int cap) {
        hits.removeIf(hit -> hit.dirKey().equals(dirKey));
        hits.addAll(matches);
        capHits(cap);
        elapsed = elapsedMillis;
        dirty = true;
    }

    public void updateProgress(int scannedDirs, int errorCount, long elapsedMillis) {
        scanned = scannedDirs;
        errors = errorCount;
        elapsed = elapsedMillis;
    }

    public void finish(int scannedDirs, int errorCount, long elapsedMillis) {
        scanned = scannedDirs;
        errors = errorCount;
        elapsed = elapsedMillis;
        finished = true;
        dirty = false;
    }

    public void markCancelled() {
        finished = true;
        dirty = false;
    }

    public void renderTable(Path root, boolean relativePaths, int cap) {
        dirty = false;
        List<FileRow> rows = hits.stream()
                .map(hit -> new FileRow(
                        hit.name(),
                        locationText(root, hit.path(), relativePaths),
                        hit.kind(),
                        hit.size(),
                        hit.modifiedEpochMillis(),
                        hit.path()))
                .sorted(FileRow.BY_NAME)
                .toList();
        if (rows.size() > cap) {
            rows = rows.subList(0, cap);
        }
        model.setRows(rows);
    }

    public String statusLine(int cap) {
        int rowCount = Math.min(hits.size(), cap);
        String detail;
        if (finished) {
            detail = Messages.SEARCH_DONE + " · 走査 " + scanned + " フォルダ";
        } else if (scanned == 0) {
            detail = Messages.SEARCH_FROM_CACHE;
        } else {
            detail = Messages.SEARCH_FROM_CACHE + " · 走査 " + scanned + " フォルダ";
        }
        if (errors > 0) {
            detail += " · 失敗 " + errors;
        }
        String filterText = filter.describe();
        if (!filterText.isEmpty()) {
            detail += " · " + filterText;
        }
        String line = Messages.items(rowCount, elapsed, Messages.SOURCE_SEARCH, detail);
        if (finished && rowCount == 0) {
            line += " · " + Messages.NO_HITS;
        }
        return line;
    }

    static SearchResultTab fromComponent(java.awt.Component component) {
        if (component instanceof javax.swing.JComponent jc) {
            Object value = jc.getClientProperty(CLIENT_KEY);
            if (value instanceof SearchResultTab tab) {
                return tab;
            }
        }
        return null;
    }

    private void capHits(int cap) {
        if (hits.size() > cap) {
            hits.subList(cap, hits.size()).clear();
        }
    }

    private static String locationText(Path root, Path path, boolean relative) {
        if (relative && root != null) {
            return ListedPaths.relativeDisplay(root, path, com.fastexplorer.util.PathStrings.isWindows());
        }
        return ListedPaths.fullDisplay(path);
    }
}
