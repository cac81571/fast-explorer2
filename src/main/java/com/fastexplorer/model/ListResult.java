package com.fastexplorer.model;

import java.util.List;

/**
 * @param complete false when the caller cancelled before the stream finished
 * @param attributeCacheHits entries whose BasicFileAttributes came from the directory enumeration itself
 * @param attributeReads entries that needed a separate {@code Files.readAttributes} call
 */
public record ListResult(
        List<FileEntry> entries,
        boolean complete,
        int attributeCacheHits,
        int attributeReads
) {
    public ListResult {
        entries = List.copyOf(entries);
    }
}
