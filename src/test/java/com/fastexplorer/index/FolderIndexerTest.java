package com.fastexplorer.index;

import com.fastexplorer.cache.ListingCache;
import com.fastexplorer.fs.DirectoryLister;
import com.fastexplorer.fs.ListingException;
import com.fastexplorer.fs.NioDirectoryLister;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.ListResult;
import com.fastexplorer.search.NameFilter;
import com.fastexplorer.util.PathStrings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderIndexerTest {
    @TempDir
    Path temp;

    private ListingCache cache;
    private IndexCatalog catalog;
    private FolderIndexer indexer;

    @AfterEach
    void tearDown() {
        if (indexer != null) {
            indexer.shutdown();
        }
        if (cache != null) {
            cache.close();
        }
        if (catalog != null) {
            catalog.close();
        }
    }

    @Test
    void indexesATempTreeIntoTheSearchCache() throws Exception {
        Path root = temp.resolve("tree");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("a.txt"), "hello");
        Files.writeString(root.resolve("sub").resolve("b.txt"), "world");
        open(new NioDirectoryLister());
        CountDownLatch done = new CountDownLatch(1);
        indexer.setListener(record -> {
            if (record.status() == IndexRecord.Status.DONE) {
                done.countDown();
            }
        });
        assertTrue(indexer.start(root, 0));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(cache.find(PathStrings.cacheKey(root.resolve("sub"))).orElseThrow().entries().stream()
                .anyMatch(entry -> entry.name().equals("b.txt")));
        assertEquals(2, cache.searchByName(PathStrings.cacheKey(root), "txt", 10).size());
        IndexRecord record = catalog.find(PathStrings.cacheKey(root)).orElseThrow();
        assertEquals(IndexRecord.Status.DONE, record.status());
        assertEquals(2, record.folderCount());
        assertEquals(2, record.fileCount());
        cache.deleteUnder(PathStrings.cacheKey(root));
        assertTrue(cache.searchByName(PathStrings.cacheKey(root), "txt", 10).isEmpty());
    }

    @Test
    void excludeDirSkipsThatFolder() throws Exception {
        Path root = temp.resolve("skip");
        Files.createDirectories(root.resolve("node_modules").resolve("pkg"));
        Files.writeString(root.resolve("node_modules").resolve("pkg").resolve("a.js"), "x");
        Files.writeString(root.resolve("keep.txt"), "k");
        open(new NioDirectoryLister());
        CountDownLatch done = new CountDownLatch(1);
        indexer.setListener(record -> {
            if (record.status() == IndexRecord.Status.DONE || record.status() == IndexRecord.Status.ERROR) {
                done.countDown();
            }
        });
        assertTrue(indexer.start(root, 0, NameFilter.parse("", "", "", "node_modules")));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(cache.find(PathStrings.cacheKey(root)).isPresent());
        assertTrue(cache.find(PathStrings.cacheKey(root.resolve("node_modules"))).isEmpty());
    }

    @Test
    void depthLimitDoesNotWalkDeeperFolders() throws Exception {
        Path root = temp.resolve("deep");
        Files.createDirectories(root.resolve("sub").resolve("nested"));
        Files.writeString(root.resolve("sub").resolve("nested").resolve("c.txt"), "c");
        open(new NioDirectoryLister());
        CountDownLatch done = new CountDownLatch(1);
        indexer.setListener(record -> {
            if (record.status() == IndexRecord.Status.DONE) {
                done.countDown();
            }
        });
        assertTrue(indexer.start(root, 1));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(cache.find(PathStrings.cacheKey(root.resolve("sub").resolve("nested"))).isEmpty());
    }

    @Test
    void cancelStopsTheWalkAndOverlappingRootsAreRejected() throws Exception {
        Path root = temp.resolve("root");
        CountDownLatch entered = new CountDownLatch(1);
        DirectoryLister lister = (dir, cancelled, deadline) -> {
            entered.countDown();
            while (!cancelled.getAsBoolean()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return new ListResult(List.of(), false, 0, 0);
        };
        open(lister);
        assertTrue(indexer.start(root, 0));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertFalse(indexer.start(root.resolve("child"), 0));
        indexer.cancel(PathStrings.cacheKey(root));
        long end = System.currentTimeMillis() + 3_000;
        IndexRecord record = null;
        while (System.currentTimeMillis() < end) {
            record = catalog.find(PathStrings.cacheKey(root)).orElse(null);
            if (record != null && record.status() == IndexRecord.Status.CANCELLED) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(IndexRecord.Status.CANCELLED, record.status());
    }

    @Test
    void reindexSkipsUnchangedDirectoryWrites() throws Exception {
        Path root = temp.resolve("stable");
        Path sub = root.resolve("sub");
        Files.createDirectories(sub);
        AtomicInteger lists = new AtomicInteger();
        AtomicInteger rootVersion = new AtomicInteger(0);
        DirectoryLister lister = (dir, cancelled, deadline) -> {
            lists.incrementAndGet();
            if (dir.equals(root)) {
                long modified = 1_000L + rootVersion.get() * 1_000L;
                return new ListResult(List.of(
                        directory("sub"),
                        new FileEntry("a.txt", EntryKind.FILE, 5, modified)), true, 0, 2);
            }
            return new ListResult(List.of(file("b.txt")), true, 0, 1);
        };
        open(lister);
        CountDownLatch first = new CountDownLatch(1);
        indexer.setListener(record -> {
            if (record.status() == IndexRecord.Status.DONE) {
                first.countDown();
            }
        });
        assertTrue(indexer.start(root, 0));
        assertTrue(first.await(5, TimeUnit.SECONDS));
        String rootKey = PathStrings.cacheKey(root);
        String subKey = PathStrings.cacheKey(sub);
        long rootListedAt = cache.find(rootKey).orElseThrow().listedAtEpochMillis();
        long subListedAt = cache.find(subKey).orElseThrow().listedAtEpochMillis();
        int listsAfterFirst = lists.get();

        Thread.sleep(5);
        CountDownLatch second = new CountDownLatch(1);
        indexer.setListener(record -> {
            if (record.status() == IndexRecord.Status.DONE) {
                second.countDown();
            }
        });
        assertTrue(indexer.start(root, 0));
        assertTrue(second.await(5, TimeUnit.SECONDS));
        assertEquals(rootListedAt, cache.find(rootKey).orElseThrow().listedAtEpochMillis());
        assertEquals(subListedAt, cache.find(subKey).orElseThrow().listedAtEpochMillis());
        assertTrue(lists.get() > listsAfterFirst);

        rootVersion.incrementAndGet();
        long afterChange = System.currentTimeMillis();
        CountDownLatch third = new CountDownLatch(1);
        indexer.setListener(record -> {
            if (record.status() == IndexRecord.Status.DONE) {
                third.countDown();
            }
        });
        assertTrue(indexer.start(root, 0));
        assertTrue(third.await(5, TimeUnit.SECONDS));
        assertTrue(cache.find(rootKey).orElseThrow().listedAtEpochMillis() >= afterChange);
        assertEquals(subListedAt, cache.find(subKey).orElseThrow().listedAtEpochMillis());
    }

    @Test
    void skipsInaccessibleFoldersAndContinues() throws Exception {
        Path root = temp.resolve("mixed");
        Path ok = root.resolve("ok");
        Path secret = root.resolve("secret");
        AtomicInteger calls = new AtomicInteger();
        DirectoryLister lister = (dir, cancelled, deadline) -> {
            calls.incrementAndGet();
            if (dir.equals(secret)) {
                throw new ListingException(ListingException.Kind.ACCESS_DENIED, "アクセスが拒否されました");
            }
            if (dir.equals(root)) {
                return new ListResult(List.of(directory("ok"), directory("secret"), file("root.txt")), true, 0, 1);
            }
            return new ListResult(List.of(file("inside.txt")), true, 0, 1);
        };
        open(lister);
        CountDownLatch done = new CountDownLatch(1);
        indexer.setListener(record -> {
            if (record.status() == IndexRecord.Status.DONE) {
                done.countDown();
            }
        });
        assertTrue(indexer.start(root, 0));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        IndexRecord record = catalog.find(PathStrings.cacheKey(root)).orElseThrow();
        assertEquals(IndexRecord.Status.DONE, record.status());
        assertTrue(record.errorCount() >= 1);
        assertTrue(cache.find(PathStrings.cacheKey(ok)).isPresent());
        assertTrue(calls.get() >= 2);
    }

    private void open(DirectoryLister lister) {
        cache = ListingCache.inMemory("idx" + System.nanoTime());
        catalog = IndexCatalog.inMemory("cat" + System.nanoTime());
        indexer = new FolderIndexer(lister, cache, catalog);
    }

    private static FileEntry file(String name) {
        return new FileEntry(name, EntryKind.FILE, 3, 10);
    }

    private static FileEntry directory(String name) {
        return new FileEntry(name, EntryKind.DIRECTORY, -1, 10);
    }
}
