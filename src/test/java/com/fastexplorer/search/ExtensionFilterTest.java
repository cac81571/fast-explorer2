package com.fastexplorer.search;

import com.fastexplorer.model.EntryKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionFilterTest {
    @Test
    void parsesMixedSeparatorsDotsAndCase() {
        ExtensionFilter filter = ExtensionFilter.parse("java, xml, .TXT; PDF\njava、Tar.GZ", true);
        assertEquals(java.util.List.of("java", "xml", "txt", "pdf", "gz"), filter.extensions());
        assertTrue(filter.includeFolders());
        assertTrue(filter.restrictsFiles());
    }

    @Test
    void blankInputMeansEveryFile() {
        ExtensionFilter filter = ExtensionFilter.parse("  ,  ; ", true);
        assertTrue(filter.extensions().isEmpty());
        assertFalse(filter.restrictsFiles());
        assertTrue(filter.accepts("noext", EntryKind.FILE));
        assertTrue(filter.accepts("docs", EntryKind.DIRECTORY));
        assertEquals("", filter.describe());
    }

    @Test
    void lastExtensionIsMatchedIgnoringCase() {
        assertEquals("gz", ExtensionFilter.extensionOf("archive.tar.gz"));
        assertEquals("txt", ExtensionFilter.extensionOf("ReadMe.TXT"));
        assertEquals("", ExtensionFilter.extensionOf("Makefile"));
        assertEquals("", ExtensionFilter.extensionOf(".gitignore"));
        assertEquals("", ExtensionFilter.extensionOf("trailing."));
        assertEquals("", ExtensionFilter.extensionOf(null));
        String hash = "0-" + "a".repeat(64);
        assertTrue(hash.length() > ExtensionFilter.MAX_STORED_EXTENSION);
        assertEquals("", ExtensionFilter.extensionOf("widget." + hash));
        assertEquals("0-abc", ExtensionFilter.extensionOf("widget.0-abc"));

        ExtensionFilter filter = ExtensionFilter.parse(".TxT", false);
        assertTrue(filter.accepts("ReadMe.TXT", EntryKind.FILE));
        assertTrue(filter.accepts("notes.txt", EntryKind.SYMLINK));
        assertFalse(filter.accepts("archive.tar.gz", EntryKind.FILE));
        assertFalse(filter.accepts("Makefile", EntryKind.FILE));
        assertFalse(filter.accepts(".gitignore", EntryKind.FILE));
        assertFalse(filter.accepts("src", EntryKind.DIRECTORY));
        assertEquals("拡張子 txt · フォルダなし", filter.describe());
    }

    @Test
    void foldersCanBeIncludedWithoutMatchingTheirNames() {
        ExtensionFilter filter = ExtensionFilter.parse("java", true);
        assertTrue(filter.accepts("src", EntryKind.DIRECTORY));
        assertTrue(filter.accepts("App.java", EntryKind.FILE));
        assertFalse(filter.accepts("App.kt", EntryKind.FILE));
        assertEquals("拡張子 java", filter.describe());
    }

    @Test
    void rejectsTokensThatAreNotExtensions() {
        ExtensionFilter filter = ExtensionFilter.parse("txt, bad/name, *.*", true);
        assertEquals(java.util.List.of("txt"), filter.extensions());
    }
}
