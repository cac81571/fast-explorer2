package com.fastexplorer.model;

import com.fastexplorer.util.PathStrings;

import java.nio.file.Path;
import java.util.Objects;

public record SearchHit(
        String dirKey,
        String name,
        EntryKind kind,
        long size,
        long modifiedEpochMillis
) {
    public SearchHit {
        Objects.requireNonNull(dirKey, "dirKey");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }

    public Path path() {
        return PathStrings.fromCacheKey(dirKey).resolve(name);
    }

    public String location() {
        return PathStrings.toDisplayPath(dirKey);
    }
}
