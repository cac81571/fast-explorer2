package com.fastexplorer.model;

import java.util.Comparator;
import java.util.Objects;

/**
 * One child of a directory. {@code size} is {@code -1} for directories and symlinks.
 */
public record FileEntry(String name, EntryKind kind, long size, long modifiedEpochMillis) {
    public static final Comparator<FileEntry> DISPLAY_ORDER = Comparator
            .comparing((FileEntry entry) -> entry.kind() != EntryKind.DIRECTORY)
            .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER);

    public FileEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }
}
