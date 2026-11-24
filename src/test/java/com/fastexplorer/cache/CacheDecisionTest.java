package com.fastexplorer.cache;

import com.fastexplorer.model.DirectorySnapshot;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheDecisionTest {
    @Test
    void missWhenAbsentOrIncomplete() {
        assertEquals(CacheDecision.MISS, CacheDecision.decide(null, 1_000, 500));
        DirectorySnapshot partial = new DirectorySnapshot("k", 1_000, false, List.of(file("a")));
        assertEquals(CacheDecision.MISS, CacheDecision.decide(partial, 1_000, 500));
    }

    @Test
    void freshInsideTtlAndStaleAfter() {
        DirectorySnapshot snapshot = new DirectorySnapshot("k", 1_000, true, List.of(file("a")));
        assertEquals(CacheDecision.FRESH, CacheDecision.decide(snapshot, 1_000, 500));
        assertEquals(CacheDecision.FRESH, CacheDecision.decide(snapshot, 1_500, 500));
        assertEquals(CacheDecision.STALE, CacheDecision.decide(snapshot, 1_501, 500));
    }

    @Test
    void zeroTtlAlwaysRevalidates() {
        DirectorySnapshot snapshot = new DirectorySnapshot("k", 1_000, true, List.of(file("a")));
        assertEquals(CacheDecision.STALE, CacheDecision.decide(snapshot, 1_000, 0));
    }

    private static FileEntry file(String name) {
        return new FileEntry(name, EntryKind.FILE, 1, 10);
    }
}
