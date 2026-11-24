package com.fastexplorer.model;

import java.util.List;

/** Cached listing of a single directory. */
public record DirectorySnapshot(
        String dirKey,
        long listedAtEpochMillis,
        boolean complete,
        List<FileEntry> entries
) {
    public DirectorySnapshot {
        entries = List.copyOf(entries);
    }
}
