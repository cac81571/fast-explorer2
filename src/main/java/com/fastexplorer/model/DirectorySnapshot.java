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

    /**
     * True when this snapshot is complete and every entry matches {@code listed}
     * (name, kind, size, modified time to the second) in order.
     * Second precision absorbs Windows FindFirstFile vs readAttributes jitter.
     */
    public boolean sameAs(List<FileEntry> listed) {
        if (!complete || listed == null || entries.size() != listed.size()) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (!sameEntry(entries.get(i), listed.get(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean sameEntry(FileEntry left, FileEntry right) {
        return left.name().equals(right.name())
                && left.kind() == right.kind()
                && left.size() == right.size()
                && left.modifiedEpochMillis() / 1000L == right.modifiedEpochMillis() / 1000L;
    }
}
