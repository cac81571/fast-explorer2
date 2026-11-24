package com.fastexplorer.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputHistoryTest {
    @TempDir
    Path temp;

    @Test
    void newestFirstAndCaseInsensitiveDedupe() {
        try (InputHistory history = InputHistory.inMemory("hist" + System.nanoTime(), 50)) {
            history.remember(InputHistory.Bucket.PATH, "D:\\Data");
            history.remember(InputHistory.Bucket.PATH, "\\\\server\\share");
            history.remember(InputHistory.Bucket.PATH, "d:\\data");
            assertEquals(List.of("d:\\data", "\\\\server\\share"), history.list(InputHistory.Bucket.PATH));
        }
    }

    @Test
    void dropsOldestBeyondTheLimit() {
        try (InputHistory history = InputHistory.inMemory("cap" + System.nanoTime(), 2)) {
            history.remember(InputHistory.Bucket.SEARCH, "one");
            history.remember(InputHistory.Bucket.SEARCH, "two");
            history.remember(InputHistory.Bucket.SEARCH, "three");
            assertEquals(List.of("three", "two"), history.list(InputHistory.Bucket.SEARCH));
        }
    }

    @Test
    void deleteAndClearDoNotAffectOtherBuckets() {
        try (InputHistory history = InputHistory.inMemory("del" + System.nanoTime(), 50)) {
            history.remember(InputHistory.Bucket.SEARCH, "alpha");
            history.remember(InputHistory.Bucket.EXTENSION, "txt, md");
            assertTrue(history.delete(InputHistory.Bucket.SEARCH, "ALPHA"));
            assertFalse(history.delete(InputHistory.Bucket.SEARCH, "missing"));
            assertTrue(history.list(InputHistory.Bucket.SEARCH).isEmpty());
            assertEquals(List.of("txt, md"), history.list(InputHistory.Bucket.EXTENSION));
            history.clear(InputHistory.Bucket.EXTENSION);
            assertTrue(history.list(InputHistory.Bucket.EXTENSION).isEmpty());
        }
    }

    @Test
    void persistsAcrossReopen(@TempDir Path dir) {
        Path database = dir.resolve("history");
        try (InputHistory history = InputHistory.open(database, 50)) {
            history.remember(InputHistory.Bucket.PATH, "\\\\server\\share\\docs");
            history.remember(InputHistory.Bucket.EXTENSION, ".TXT");
        }
        try (InputHistory reopened = InputHistory.open(database, 50)) {
            assertEquals(List.of("\\\\server\\share\\docs"), reopened.list(InputHistory.Bucket.PATH));
            assertEquals(List.of(".TXT"), reopened.list(InputHistory.Bucket.EXTENSION));
            reopened.clearAll();
            assertTrue(reopened.list(InputHistory.Bucket.PATH).isEmpty());
        }
    }
}
