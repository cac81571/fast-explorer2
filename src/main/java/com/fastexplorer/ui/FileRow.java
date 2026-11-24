package com.fastexplorer.ui;

import com.fastexplorer.model.EntryKind;

import java.nio.file.Path;
import java.util.Comparator;

public record FileRow(
        String name,
        String location,
        EntryKind kind,
        long size,
        long modifiedEpochMillis,
        Path path
) {
    public static final Comparator<FileRow> BY_NAME = Comparator
            .comparing((FileRow row) -> row.kind() != EntryKind.DIRECTORY)
            .thenComparing(FileRow::name, String.CASE_INSENSITIVE_ORDER);
}
