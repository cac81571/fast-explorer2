package com.fastexplorer.search;

import com.fastexplorer.model.EntryKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameFilterTest {
    @Test
    void parsesSeparatorsAndTreatsEmptyIncludeAsEverything() {
        NameFilter filter = NameFilter.parse("ReadMe, *.log;file?", "tmp notes", "", "");
        assertTrue(filter.acceptsFileName("readme.txt"));
        assertTrue(filter.acceptsFileName("app.LOG"));
        assertTrue(filter.acceptsFileName("fileX"));
        assertFalse(filter.acceptsFileName("notes.tmp"));
        assertFalse(filter.acceptsFileName("tmp.txt"));
        assertFalse(filter.acceptsFileName("other"));
        assertTrue(NameFilter.all().acceptsFileName("anything"));
        assertTrue(NameFilter.parse("", "", "", "").accepts("src", "A.java", EntryKind.FILE));
    }

    @Test
    void wildcardsAndSubstringsAreCaseInsensitive() {
        NameFilter filter = NameFilter.parse("*report*", "draft?", "", "");
        assertTrue(filter.acceptsFileName("Q3-Report-final.PDF"));
        assertFalse(filter.acceptsFileName("summary.txt"));
        assertFalse(filter.acceptsFileName("draft1"));
        assertFalse(filter.acceptsFileName("draft10"));
    }

    @Test
    void directoryIncludeRequiresAFolderOnTheRelativePath() {
        NameFilter filter = NameFilter.parse("*.txt", "", "src", "");
        assertTrue(filter.accepts("src", "App.txt", EntryKind.FILE));
        assertTrue(filter.accepts("src/main", "App.txt", EntryKind.FILE));
        assertTrue(filter.accepts("", "src", EntryKind.DIRECTORY));
        assertFalse(filter.accepts("", "notes.txt", EntryKind.FILE));
        assertFalse(filter.accepts("docs", "App.txt", EntryKind.FILE));
    }

    @Test
    void directoryExcludeMatchesAnySegmentAndPrunesTheWalk() {
        NameFilter filter = NameFilter.parse("", "", "", "node_modules, .git, target");
        assertTrue(filter.excludesDirectory("", "node_modules"));
        assertTrue(filter.excludesDirectory("pkg", ".git"));
        assertTrue(filter.excludesDirectory("build", "target"));
        assertFalse(filter.excludesDirectory("src", "main"));
        assertFalse(filter.accepts("src/node_modules", "index.js", EntryKind.FILE));
        assertFalse(filter.accepts("src", "node_modules", EntryKind.DIRECTORY));
        assertTrue(filter.accepts("src", "App.java", EntryKind.FILE));
    }

    @Test
    void slashPatternMatchesTheWholeRelativePath() {
        NameFilter filter = NameFilter.parse("", "", "src/main", "vendor/*");
        assertTrue(filter.accepts("src/main", "App.java", EntryKind.FILE));
        assertFalse(filter.accepts("src", "App.java", EntryKind.FILE));
        assertTrue(filter.excludesDirectory("vendor", "leftpad"));
        assertFalse(filter.excludesDirectory("src", "main"));
    }

    @Test
    void fileExcludeDoesNotHideDirectories() {
        NameFilter filter = NameFilter.parse("", "*.log", "", "");
        assertFalse(filter.acceptsFileName("app.log"));
        assertTrue(filter.accepts("", "logs", EntryKind.DIRECTORY));
    }
}
