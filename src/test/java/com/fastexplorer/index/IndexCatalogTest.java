package com.fastexplorer.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexCatalogTest {
    @TempDir
    Path temp;

    @Test
    void savesProgressAndPersistsStatus() {
        Path database = temp.resolve("index-meta");
        IndexRecord running = new IndexRecord(
                "/share/docs", "\\\\server\\share\\docs", IndexRecord.Status.RUNNING,
                1_000, 0, 2, 5, 40, 0, "", 0);
        try (IndexCatalog catalog = IndexCatalog.open(database)) {
            catalog.save(running);
            catalog.save(running.withProgress(4, 9, 1, 80));
            IndexRecord stored = catalog.find("/share/docs").orElseThrow();
            assertEquals(IndexRecord.Status.RUNNING, stored.status());
            assertEquals(4, stored.folderCount());
            assertEquals(9, stored.fileCount());
            catalog.save(new IndexRecord(
                    "/share/docs", "\\\\server\\share\\docs", IndexRecord.Status.DONE,
                    1_000, 2_000, 4, 9, 1_000, 1, "", 0));
            catalog.save(new IndexRecord(
                    "/other", "D:\\other", IndexRecord.Status.ERROR,
                    3_000, 3_100, 0, 0, 100, 1, "アクセスが拒否されました", 1));
        }
        try (IndexCatalog reopened = IndexCatalog.open(database)) {
            assertEquals(IndexRecord.Status.DONE, reopened.find("/share/docs").orElseThrow().status());
            assertEquals("\\\\server\\share\\docs", reopened.find("/share/docs").orElseThrow().displayPath());
            reopened.delete("/other");
            assertTrue(reopened.find("/other").isEmpty());
            assertEquals(1, reopened.list().size());
        }
    }
}
