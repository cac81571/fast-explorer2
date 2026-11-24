package com.fastexplorer.nav;

import com.fastexplorer.cache.ListingCache;
import com.fastexplorer.config.ExplorerSettings;
import com.fastexplorer.fs.DirectoryLister;
import com.fastexplorer.fs.ListingException;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.ListResult;
import com.fastexplorer.model.LoadStatus;
import com.fastexplorer.model.SearchHit;
import com.fastexplorer.util.PathStrings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ExplorerControllerTest {
    @TempDir
    Path temp;

    private ListingCache cache;
    private ExplorerController controller;
    private Recording listener;
    private AtomicInteger listCalls;
    private DirectoryLister lister;

    @BeforeEach
    void setUp() {
        cache = ListingCache.inMemory("nav" + System.nanoTime());
        listener = new Recording();
        listCalls = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.shutdown();
        }
        cache.close();
    }

    @Test
    void freshCacheDoesNotTouchTheFilesystem() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("docs"));
        cache.replace(PathStrings.cacheKey(dir), List.of(file("cached.txt")), System.currentTimeMillis(), true);
        lister = (path, cancelled, deadline) -> {
            listCalls.incrementAndGet();
            return new ListResult(List.of(file("from-disk.txt")), true, 0, 1);
        };
        start(60_000, 5_000);
        controller.open(dir, true, false);
        Event event = listener.await(Event.Type.LISTING);
        assertEquals(List.of("cached.txt"), event.names);
        assertEquals(LoadStatus.Origin.CACHE, event.origin);
        assertEquals(0, listCalls.get());
    }

    @Test
    void staleCachePaintsThenReplaces() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("share"));
        cache.replace(PathStrings.cacheKey(dir), List.of(file("old.txt")), 1, true);
        CountDownLatch release = new CountDownLatch(1);
        lister = (path, cancelled, deadline) -> {
            listCalls.incrementAndGet();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ListResult(List.of(), false, 0, 0);
            }
            return new ListResult(List.of(file("fresh.txt")), true, 2, 1);
        };
        start(1_000, 5_000);
        controller.open(dir, true, false);
        Event first = listener.await(Event.Type.LISTING);
        assertEquals(List.of("old.txt"), first.names);
        assertEquals(LoadStatus.Origin.REFRESHING, first.origin);
        release.countDown();
        Event second = listener.await(Event.Type.LISTING);
        assertEquals(List.of("fresh.txt"), second.names);
        assertEquals(LoadStatus.Origin.FILESYSTEM, second.origin);
        assertEquals("fresh.txt", cache.find(PathStrings.cacheKey(dir)).orElseThrow().entries().get(0).name());
        assertTrue(second.detail.contains("属性キャッシュ"));
    }

    @Test
    void failedRefreshKeepsTheCachedRows() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("share"));
        cache.replace(PathStrings.cacheKey(dir), List.of(file("old.txt")), 1, true);
        lister = (path, cancelled, deadline) -> {
            throw new ListingException(ListingException.Kind.ACCESS_DENIED, "アクセスが拒否されました");
        };
        start(1_000, 5_000);
        controller.open(dir, true, false);
        Event listing = listener.await(Event.Type.LISTING);
        Event error = listener.await(Event.Type.ERROR);
        assertEquals(List.of("old.txt"), listing.names);
        assertTrue(error.keepRows);
        assertTrue(error.message.contains("アクセスが拒否されました"));
        assertEquals("old.txt", cache.find(PathStrings.cacheKey(dir)).orElseThrow().entries().get(0).name());
    }

    @Test
    void openReturnsWhileListingIsStillBlocked() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("slow"));
        CountDownLatch release = new CountDownLatch(1);
        lister = (path, cancelled, deadline) -> {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    return new ListResult(List.of(), false, 0, 0);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ListResult(List.of(), false, 0, 0);
            }
            return new ListResult(List.of(file("x.txt")), true, 0, 1);
        };
        start(60_000, 5_000);
        long started = System.nanoTime();
        controller.open(dir, true, false);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsedMs < 1_000, "open blocked the caller for " + elapsedMs + " ms");
        release.countDown();
    }

    @Test
    void navigatingAwayDropsThePreviousListing() throws Exception {
        Path first = Files.createDirectory(temp.resolve("A"));
        Path second = Files.createDirectory(temp.resolve("B"));
        CountDownLatch firstStarted = new CountDownLatch(1);
        lister = (path, cancelled, deadline) -> {
            if (path.getFileName().toString().equals("A")) {
                firstStarted.countDown();
                try {
                    while (!cancelled.getAsBoolean()) {
                        Thread.sleep(5);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ListResult(List.of(file("DROPPED")), false, 0, 0);
                }
                return new ListResult(List.of(file("DROPPED")), false, 0, 0);
            }
            return new ListResult(List.of(file("OK-B")), true, 0, 1);
        };
        start(60_000, 5_000);
        controller.open(first, true, true);
        assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
        controller.open(second, true, true);
        Event listing = listener.awaitName("OK-B");
        assertEquals(second, listing.directory);
        Thread.sleep(50);
        assertTrue(listener.listings.stream().noneMatch(event -> event.names.contains("DROPPED")));
    }

    @Test
    void backForwardAndUpFollowHistory() throws Exception {
        Path a = Files.createDirectories(temp.resolve("a"));
        Path b = Files.createDirectories(a.resolve("b"));
        lister = (path, cancelled, deadline) -> new ListResult(List.of(file(path.getFileName() + ".txt")), true, 0, 1);
        start(60_000, 5_000);
        controller.open(a, true, false);
        listener.await(Event.Type.LISTING);
        controller.open(b, true, false);
        listener.await(Event.Type.LISTING);
        controller.back();
        Event back = listener.await(Event.Type.LISTING);
        assertEquals(a, back.directory);
        controller.forward();
        Event forward = listener.await(Event.Type.LISTING);
        assertEquals(b, forward.directory);
        controller.up();
        Event up = listener.await(Event.Type.LISTING);
        assertEquals(a, up.directory);
    }

    @Test
    void filePathOpensTheParentAndRemembersTheName() throws Exception {
        Path file = temp.resolve("a.txt");
        Files.writeString(file, "x");
        lister = (path, cancelled, deadline) -> {
            if (path.getFileName() != null && path.getFileName().toString().equals("a.txt")) {
                throw new ListingException(ListingException.Kind.NOT_DIRECTORY, "フォルダではありません");
            }
            return new ListResult(List.of(file("a.txt"), dir("sub")), true, 0, 1);
        };
        start(60_000, 5_000);
        controller.open(file, true, false);
        Event listing = listener.await(Event.Type.LISTING);
        assertEquals(temp, listing.directory);
        assertEquals("a.txt", controller.pendingSelection());
        assertFalse(listener.locations.getLast().canBack());
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    void timeoutSurfacesWithoutBlockingTheCaller() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("hung"));
        lister = (path, cancelled, deadline) -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ListResult(List.of(), false, 0, 0);
            }
            return new ListResult(List.of(), true, 0, 0);
        };
        start(60_000, 300);
        long started = System.nanoTime();
        controller.open(dir, true, true);
        assertTrue((System.nanoTime() - started) / 1_000_000L < 1_000);
        Event error = listener.await(Event.Type.ERROR);
        assertTrue(error.message.contains("タイムアウト"));
        assertFalse(error.keepRows);
    }

    private void start(long ttlMillis, long timeoutMillis) {
        controller = new ExplorerController(
                lister,
                cache,
                new ExplorerSettings(ttlMillis, timeoutMillis, 100, 100),
                Runnable::run,
                listener);
    }

    private static FileEntry file(String name) {
        return new FileEntry(name, EntryKind.FILE, 3, 20);
    }

    private static FileEntry dir(String name) {
        return new FileEntry(name, EntryKind.DIRECTORY, -1, 20);
    }

    private static final class Recording implements ExplorerController.Listener {
        final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        final List<Event> listings = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Location> locations = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        Event await(Event.Type type) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 4_000;
            while (System.currentTimeMillis() < deadline) {
                Event event = events.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (event == null) {
                    break;
                }
                if (event.type == type) {
                    return event;
                }
            }
            return fail("timed out waiting for " + type);
        }

        Event awaitName(String name) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 4_000;
            while (System.currentTimeMillis() < deadline) {
                Event event = await(Event.Type.LISTING);
                if (event.names.contains(name)) {
                    return event;
                }
            }
            return fail("timed out waiting for " + name);
        }

        @Override
        public void onLoadStarted(Path directory) {
            events.add(Event.of(Event.Type.STARTED, directory, List.of(), null, null, false));
        }

        @Override
        public void onLocation(String displayPath, boolean canBack, boolean canForward, boolean canUp) {
            locations.add(new Location(displayPath, canBack, canForward, canUp));
        }

        @Override
        public void onListing(Path directory, List<FileEntry> entries, LoadStatus status) {
            Event event = Event.of(
                    Event.Type.LISTING,
                    directory,
                    entries.stream().map(FileEntry::name).toList(),
                    status.origin(),
                    status.detail(),
                    false);
            listings.add(event);
            events.add(event);
        }

        @Override
        public void onError(String message, boolean keepRows) {
            errors.add(message);
            events.add(Event.of(Event.Type.ERROR, null, List.of(), null, message, keepRows));
        }

        @Override
        public void onSearchStarted(String query) {
        }

        @Override
        public void onSearchHits(List<SearchHit> cacheHits, long elapsedMillis) {
        }

        @Override
        public void onSearchDirectory(String dirKey, List<SearchHit> matches, long elapsedMillis) {
        }

        @Override
        public void onSearchProgress(int scannedDirs, int errors, long elapsedMillis) {
        }

        @Override
        public void onSearchFinished(int scannedDirs, int errors, long elapsedMillis) {
        }
    }

    private record Location(String path, boolean canBack, boolean canForward, boolean canUp) {
    }

    private record Event(
            Type type,
            Path directory,
            List<String> names,
            LoadStatus.Origin origin,
            String message,
            String detail,
            boolean keepRows
    ) {
        enum Type { STARTED, LISTING, ERROR }

        static Event of(
                Type type,
                Path directory,
                List<String> names,
                LoadStatus.Origin origin,
                String text,
                boolean keepRows
        ) {
            String message = type == Type.ERROR ? text : null;
            String detail = type == Type.LISTING ? text : null;
            return new Event(type, directory, names, origin, message, detail, keepRows);
        }
    }
}
