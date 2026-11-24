package com.fastexplorer.filecache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileContentCacheTest {
    @TempDir
    Path temp;

    @Test
    void mapsUncAndLocalPathsWithoutCollisions() {
        Path root = temp.resolve("cache");
        Path unc = FileCachePaths.resolve(root, "\\\\server\\share\\docs\\a.txt");
        Path other = FileCachePaths.resolve(root, "\\\\other\\share\\docs\\a.txt");
        Path local = FileCachePaths.resolve(root, "D:\\data\\a.txt");
        assertNotEquals(unc, other);
        assertNotEquals(unc, local);
        assertTrue(unc.startsWith(root.resolve("unc")));
        assertTrue(local.startsWith(root.resolve("local")));
        assertEquals(unc, FileCachePaths.resolve(root, "//server/share/docs/a.txt"));
        assertEquals(root.resolve("unc").resolve("server").resolve("share").resolve("bar.txt"),
                FileCachePaths.resolve(root, "\\\\server\\share\\foo\\..\\bar.txt"));
        assertThrows(IllegalArgumentException.class, () -> FileCachePaths.resolve(root, "\\\\server\\..\\..\\evil"));
        assertEquals(List.of("explorer.exe", "\\\\server\\share\\docs"),
                WindowsExplorer.openFolderCommand("\\\\server\\share\\docs"));
        assertEquals(List.of("explorer.exe", "/select,\\\\server\\share\\docs\\a.txt"),
                WindowsExplorer.selectFileCommand("\\\\server\\share\\docs\\a.txt"));
    }

    @Test
    void reusesCopyWhenSizeAndModifiedTimeMatch() throws Exception {
        Path root = temp.resolve("cache");
        Path source = temp.resolve("origin.txt");
        Files.write(source, "hello-cache".getBytes());
        long modified = 50_000L;
        try (FileContentCache cache = FileContentCache.inMemory("hit" + System.nanoTime(), root, 0, new AtomicLong(1)::incrementAndGet)) {
            Path copy = cache.materialize(source, "\\\\server\\share\\origin.txt", Files.size(source), modified, false, () -> false, null);
            byte[] cached = "HELLO-CACHE".getBytes();
            Files.write(copy, cached);
            Path again = cache.materialize(source, "\\\\server\\share\\origin.txt", Files.size(source), modified, false, () -> false, null);
            assertEquals(copy, again);
            assertArrayEquals(cached, Files.readAllBytes(again));
            Path refreshed = cache.materialize(source, "\\\\server\\share\\origin.txt", Files.size(source), modified + 1, false, () -> false, null);
            assertArrayEquals("hello-cache".getBytes(), Files.readAllBytes(refreshed));
            assertEquals(modified + 1, Files.getLastModifiedTime(refreshed).toMillis());
        }
    }

    @Test
    void readOnlyCopyCanBeReplacedAfterClearingTheFlag() throws Exception {
        Path root = temp.resolve("cache");
        Path source = temp.resolve("note.txt");
        Files.writeString(source, "first");
        Path updated = temp.resolve("note2.txt");
        Files.writeString(updated, "second!");
        try (FileContentCache cache = FileContentCache.inMemory("ro" + System.nanoTime(), root, 0, System::currentTimeMillis)) {
            Path copy = cache.materialize(source, "\\\\server\\share\\note.txt", Files.size(source), 10, true, () -> false, null);
            if (Files.isWritable(copy)) {
                org.junit.jupiter.api.Assumptions.abort("read-only flag is ignored for this user");
            }
            Path replaced = cache.materialize(updated, "\\\\server\\share\\note.txt", Files.size(updated), 11, false, () -> false, null);
            assertEquals("second!", Files.readString(replaced));
            assertTrue(Files.isWritable(replaced));
            assertTrue(Files.list(replaced.getParent()).noneMatch(path -> path.getFileName().toString().contains(".part-")));
        }
    }

    @Test
    void evictsLeastRecentlyUsedCopies() throws Exception {
        Path root = temp.resolve("cache");
        AtomicLong clock = new AtomicLong(10);
        try (FileContentCache cache = FileContentCache.inMemory("lru" + System.nanoTime(), root, 0, clock::incrementAndGet)) {
            Path a = write(temp.resolve("a.bin"), 100);
            Path b = write(temp.resolve("b.bin"), 200);
            Path c = write(temp.resolve("c.bin"), 400);
            cache.materialize(a, "\\\\server\\share\\a.bin", 100, 1, false, () -> false, null);
            cache.materialize(b, "\\\\server\\share\\b.bin", 200, 1, false, () -> false, null);
            cache.materialize(c, "\\\\server\\share\\c.bin", 400, 1, false, () -> false, null);
            cache.evictTo(500, FileCachePaths.sourceKey("\\\\server\\share\\c.bin"));
            assertEquals(400, cache.totalBytes());
            assertFalse(Files.exists(FileCachePaths.resolve(root, "\\\\server\\share\\a.bin")));
            assertFalse(Files.exists(FileCachePaths.resolve(root, "\\\\server\\share\\b.bin")));
            assertTrue(Files.exists(FileCachePaths.resolve(root, "\\\\server\\share\\c.bin")));
        }
    }

    @Test
    void cancelRemovesThePartialFile() throws Exception {
        Path root = temp.resolve("cache");
        Path source = write(temp.resolve("big.bin"), 1024);
        try (FileContentCache cache = FileContentCache.inMemory("cancel" + System.nanoTime(), root, 0, System::currentTimeMillis)) {
            assertThrows(CancellationException.class, () -> cache.materialize(
                    source, "\\\\server\\share\\big.bin", 1024, 1, false, () -> true, null));
            Path parent = FileCachePaths.resolve(root, "\\\\server\\share\\big.bin").getParent();
            if (Files.exists(parent)) {
                try (var list = Files.list(parent)) {
                    assertTrue(list.noneMatch(path -> path.getFileName().toString().contains(".part-")));
                }
            }
        }
    }

    private static Path write(Path path, int size) throws Exception {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) i;
        }
        Files.write(path, bytes);
        return path;
    }
}
