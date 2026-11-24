package com.fastexplorer.search;

import com.fastexplorer.cache.ListingCache;
import com.fastexplorer.config.ExplorerSettings;
import com.fastexplorer.fs.DirectoryLister;
import com.fastexplorer.fs.ListingException;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.ListResult;
import com.fastexplorer.model.SearchHit;
import com.fastexplorer.util.PathStrings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {
    @TempDir
    Path temp;

    private ListingCache cache;
    private ExecutorService io;
    private final Set<Path> listed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger listCalls = new AtomicInteger();
    private final AtomicBoolean cancelAfterFirstList = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        cache = ListingCache.inMemory("searchsvc" + System.nanoTime());
        io = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "search-test-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        io.shutdownNow();
        cache.close();
    }

    @Test
    void cacheHitsArriveBeforeMissingDirectoriesAreWalked() {
        Path root = temp.resolve("root");
        Path sub = root.resolve("sub");
        Path other = root.resolve("other");
        long fresh = System.currentTimeMillis();
        cache.replace(PathStrings.cacheKey(root), List.of(
                file("hit.txt"),
                dir("sub"),
                dir("other")), fresh, true);

        Recording listener = new Recording();
        service().search(root, "hit", () -> false, listener);

        assertEquals("cache", listener.order.get(0));
        assertTrue(listener.cacheNames.contains("hit.txt"));
        assertTrue(listener.updatedNames.contains("hit-sub.txt"));
        assertTrue(listed.contains(sub));
        assertTrue(listed.contains(other));
        assertTrue(listed.stream().noneMatch(path -> path.equals(root)));
        assertTrue(listener.finished);
        assertEquals(0, listener.errors);
    }

    @Test
    void staleDirectoryIsRelistedAndReplacesHits() {
        Path root = temp.resolve("root");
        cache.replace(PathStrings.cacheKey(root), List.of(file("hit-old.txt"), dir("gone")), 1, true);
        Recording listener = new Recording();
        service().search(root, "hit", () -> false, listener);
        assertTrue(listener.cacheNames.contains("hit-old.txt"));
        assertTrue(listener.updatedNames.contains("hit-new.txt"));
        assertTrue(listed.contains(root));
    }

    @Test
    void accessDeniedSubdirectoryDoesNotStopTheWalk() {
        Path root = temp.resolve("root");
        cache.replace(PathStrings.cacheKey(root), List.of(dir("denied"), dir("ok")), System.currentTimeMillis(), true);
        Recording listener = new Recording();
        service().search(root, "hit", () -> false, listener);
        assertTrue(listener.updatedNames.contains("hit-ok.txt"));
        assertTrue(listener.errors >= 1);
        assertTrue(listener.finished);
    }

    @Test
    void cancellationStopsTheWalk() {
        Path root = temp.resolve("root");
        cancelAfterFirstList.set(true);
        Recording listener = new Recording();
        service().search(root, "hit", () -> listCalls.get() > 0, listener);
        assertTrue(listener.cancelled || listCalls.get() <= 2);
        assertTrue(listCalls.get() < 5);
    }

    @Test
    void extensionFilterAppliesToIndexedHitsAndToTheFilesystemWalk() {
        Path root = temp.resolve("root");
        long fresh = System.currentTimeMillis();
        cache.replace(PathStrings.cacheKey(root), List.of(
                file("notes.txt"),
                file("data.csv"),
                dir("src")), fresh, true);
        java.util.Set<String> walked = ConcurrentHashMap.newKeySet();
        DirectoryLister filteringLister = (dir, cancelled, deadline) -> {
            walked.add(dir.getFileName().toString());
            String name = dir.getFileName().toString();
            List<FileEntry> entries = switch (name) {
                case "src" -> List.of(file("App.java"), file("readme.md"), dir("nested"));
                case "nested" -> List.of(file("Inner.TXT"));
                default -> List.of();
            };
            return new ListResult(entries, true, 0, entries.size());
        };
        Recording listener = new Recording();
        new SearchService(cache, filteringLister, io, new ExplorerSettings(60_000, 2_000, 100, 100))
                .search(root, ".", () -> false, listener, ExtensionFilter.parse("txt, java", false));
        assertEquals(List.of("notes.txt"), listener.cacheNames);
        assertTrue(listener.updatedNames.contains("App.java"));
        assertTrue(listener.updatedNames.contains("Inner.TXT"));
        assertTrue(listener.updatedNames.stream().noneMatch(name -> name.equals("readme.md") || name.equals("data.csv") || name.equals("src")));
        assertTrue(walked.contains("src"));
        assertTrue(walked.contains("nested"));
        assertTrue(walked.stream().noneMatch(name -> name.equals("root")));
    }

    @Test
    void excludedDirectoriesAreNotWalked() {
        Path root = temp.resolve("root");
        long fresh = System.currentTimeMillis();
        cache.replace(PathStrings.cacheKey(root), List.of(
                file("keep.txt"),
                dir("src"),
                dir("node_modules")), fresh, true);
        java.util.Set<String> walked = ConcurrentHashMap.newKeySet();
        DirectoryLister filteringLister = (dir, cancelled, deadline) -> {
            walked.add(dir.getFileName().toString());
            String name = dir.getFileName().toString();
            List<FileEntry> entries = switch (name) {
                case "src" -> List.of(file("App.java"));
                case "node_modules" -> List.of(file("leftpad.js"));
                default -> List.of();
            };
            return new ListResult(entries, true, 0, entries.size());
        };
        Recording listener = new Recording();
        new SearchService(cache, filteringLister, io, new ExplorerSettings(60_000, 2_000, 100, 100))
                .search(root, NameFilter.parse("", "", "", "node_modules"), () -> false, listener, ExtensionFilter.all());
        assertTrue(listener.cacheNames.contains("keep.txt"));
        assertTrue(listener.cacheNames.stream().noneMatch(name -> name.equals("node_modules")));
        assertTrue(walked.contains("src"));
        assertTrue(walked.stream().noneMatch(name -> name.equals("node_modules")));
        assertTrue(listener.updatedNames.contains("App.java"));
        assertTrue(listener.updatedNames.stream().noneMatch(name -> name.equals("leftpad.js")));
    }

    @Test
    void hashLikeSuffixDoesNotAbortTheSearch() {
        Path root = temp.resolve("root");
        String hashed = "widget.0-" + "a".repeat(64);
        DirectoryLister lister = (dir, cancelled, deadline) ->
                new ListResult(List.of(file(hashed), file("notes.txt")), true, 0, 2);
        Recording listener = new Recording();
        new SearchService(cache, lister, io, new ExplorerSettings(60_000, 2_000, 100, 100))
                .search(root, NameFilter.all(), () -> false, listener, ExtensionFilter.all());
        assertTrue(listener.finished);
        assertTrue(listener.updatedNames.contains(hashed));
        assertTrue(listener.updatedNames.contains("notes.txt"));
        assertTrue(cache.find(PathStrings.cacheKey(root)).orElseThrow().entries().stream()
                .anyMatch(entry -> entry.name().equals(hashed)));
    }

    private SearchService service() {
        DirectoryLister lister = (dir, cancelled, deadline) -> {
            listCalls.incrementAndGet();
            if (cancelled.getAsBoolean()) {
                return new ListResult(List.of(), false, 0, 0);
            }
            listed.add(dir);
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (name.equals("denied")) {
                throw new ListingException(ListingException.Kind.ACCESS_DENIED, "アクセスが拒否されました");
            }
            List<FileEntry> entries = switch (name) {
                case "sub" -> List.of(file("hit-sub.txt"), file("other.txt"));
                case "other" -> List.of(file("plain.txt"));
                case "ok" -> List.of(file("hit-ok.txt"));
                case "root" -> List.of(file("hit-new.txt"));
                default -> List.of(file("hit-new.txt"), dir("sub"));
            };
            return new ListResult(entries, true, 0, entries.size());
        };
        return new SearchService(cache, lister, io, new ExplorerSettings(60_000, 2_000, 100, 100));
    }

    private static FileEntry file(String name) {
        return new FileEntry(name, EntryKind.FILE, 4, 10);
    }

    private static FileEntry dir(String name) {
        return new FileEntry(name, EntryKind.DIRECTORY, -1, 10);
    }

    private static final class Recording implements SearchService.Listener {
        final List<String> order = new ArrayList<>();
        final List<String> cacheNames = new ArrayList<>();
        final List<String> updatedNames = new ArrayList<>();
        int errors;
        boolean finished;
        boolean cancelled;

        @Override
        public void onCacheHits(List<SearchHit> hits) {
            order.add("cache");
            hits.forEach(hit -> cacheNames.add(hit.name()));
        }

        @Override
        public void onDirectoryUpdated(String dirKey, List<SearchHit> matches) {
            order.add("dir");
            matches.forEach(hit -> updatedNames.add(hit.name()));
        }

        @Override
        public void onProgress(int scannedDirs, int errors) {
            this.errors = errors;
        }

        @Override
        public void onFinished(int scannedDirs, int errors, boolean cancelled) {
            this.errors = errors;
            this.finished = true;
            this.cancelled = cancelled;
        }
    }
}
