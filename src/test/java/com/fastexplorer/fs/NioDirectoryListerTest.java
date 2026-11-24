package com.fastexplorer.fs;

import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.ListResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioDirectoryListerTest {
    @TempDir
    Path temp;

    private final NioDirectoryLister lister = new NioDirectoryLister();

    @Test
    void listsNamesKindsAndSizesFromATempDirectory() throws Exception {
        Files.writeString(temp.resolve("b.txt"), "hello");
        Files.writeString(temp.resolve("日本語 メモ.txt"), "あ");
        Files.createDirectory(temp.resolve("sub"));
        Files.writeString(temp.resolve(".hidden"), "x");
        Instant stamp = Instant.parse("2024-05-01T00:00:00Z");
        Files.setLastModifiedTime(temp.resolve("b.txt"), FileTime.from(stamp));

        ListResult result = lister.list(temp, () -> false, System.currentTimeMillis() + 10_000);

        assertTrue(result.complete());
        assertEquals(4, result.entries().size());
        assertEquals("sub", result.entries().get(0).name());
        assertEquals(EntryKind.DIRECTORY, result.entries().get(0).kind());
        assertEquals(-1, result.entries().get(0).size());

        FileEntry b = result.entries().stream().filter(e -> e.name().equals("b.txt")).findFirst().orElseThrow();
        assertEquals(EntryKind.FILE, b.kind());
        assertEquals(5, b.size());
        assertEquals(stamp.toEpochMilli(), b.modifiedEpochMillis());
        assertTrue(result.entries().stream().anyMatch(e -> e.name().equals("日本語 メモ.txt") && e.size() == "あ".getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        assertTrue(result.entries().stream().anyMatch(e -> e.name().equals(".hidden")));
        assertTrue(result.entries().get(0).kind() == EntryKind.DIRECTORY);
    }

    @Test
    void cancellationStopsBeforeTheWholeDirectory() throws Exception {
        for (int i = 0; i < 12; i++) {
            Files.writeString(temp.resolve("f" + i + ".txt"), "x");
        }
        AtomicInteger checks = new AtomicInteger();
        ListResult result = lister.list(
                temp,
                () -> checks.incrementAndGet() > 2,
                System.currentTimeMillis() + 10_000);
        assertFalse(result.complete());
        assertTrue(result.entries().size() < 12);
    }

    @Test
    void alreadyExpiredDeadlineTimesOutWithoutNeedingASlowDisk() {
        ListingException ex = assertThrows(ListingException.class,
                () -> lister.list(temp, () -> false, System.currentTimeMillis() - 5));
        assertEquals(ListingException.Kind.TIMEOUT, ex.kind());
    }

    @Test
    void missingPathAndFilePathAreDistinctErrors() throws Exception {
        ListingException missing = assertThrows(ListingException.class,
                () -> lister.list(temp.resolve("nope"), () -> false, System.currentTimeMillis() + 5_000));
        assertEquals(ListingException.Kind.NOT_FOUND, missing.kind());

        Path file = temp.resolve("a.txt");
        Files.writeString(file, "x");
        ListingException notDir = assertThrows(ListingException.class,
                () -> lister.list(file, () -> false, System.currentTimeMillis() + 5_000));
        assertEquals(ListingException.Kind.NOT_DIRECTORY, notDir.kind());
    }

    @Test
    @DisabledIf("runningAsRoot")
    void accessDeniedIsReported() throws Exception {
        Path locked = Files.createDirectory(temp.resolve("locked"));
        Files.writeString(locked.resolve("secret.txt"), "x");
        boolean changed = locked.toFile().setReadable(false, false) && locked.toFile().setExecutable(false, false);
        if (!changed || Files.isReadable(locked)) {
            locked.toFile().setReadable(true, false);
            locked.toFile().setExecutable(true, false);
            org.junit.jupiter.api.Assumptions.abort("directory permissions cannot be removed");
        }
        try {
            ListingException ex = assertThrows(ListingException.class,
                    () -> lister.list(locked, () -> false, System.currentTimeMillis() + 5_000));
            assertEquals(ListingException.Kind.ACCESS_DENIED, ex.kind());
        } finally {
            locked.toFile().setReadable(true, false);
            locked.toFile().setExecutable(true, false);
        }
    }

    @Test
    void unreachableAndDeniedMessagesMapToKinds() {
        ListingException unreachable = NioDirectoryLister.map(
                new FileSystemException("\\\\server\\share", null, "No route to host"));
        assertEquals(ListingException.Kind.UNREACHABLE, unreachable.kind());
        ListingException denied = NioDirectoryLister.map(
                new java.nio.file.AccessDeniedException("\\\\server\\share\\secret"));
        assertEquals(ListingException.Kind.ACCESS_DENIED, denied.kind());
    }

    static boolean runningAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }
}
