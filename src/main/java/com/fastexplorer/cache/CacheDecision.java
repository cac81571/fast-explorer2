package com.fastexplorer.cache;

import com.fastexplorer.model.DirectorySnapshot;

/**
 * How a cached listing should be used.
 * {@link #FRESH} paints the cache and stops.
 * {@link #STALE} paints the cache, then lists the filesystem and replaces the table.
 * {@link #MISS} lists the filesystem.
 */
public enum CacheDecision {
    MISS,
    FRESH,
    STALE;

    public static CacheDecision decide(DirectorySnapshot snapshot, long nowMillis, long ttlMillis) {
        if (snapshot == null || !snapshot.complete()) {
            return MISS;
        }
        if (ttlMillis <= 0) {
            return STALE;
        }
        long age = nowMillis - snapshot.listedAtEpochMillis();
        if (age <= ttlMillis) {
            return FRESH;
        }
        return STALE;
    }
}
