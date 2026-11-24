package com.fastexplorer.index;

import java.util.Objects;

/** One indexed folder root and the last time a walk was started for it. */
public record IndexRecord(
        String rootKey,
        String displayPath,
        Status status,
        long startedAt,
        long finishedAt,
        int folderCount,
        int fileCount,
        long durationMs,
        int errorCount,
        String message,
        int depthLimit
) {
    public enum Status {
        RUNNING,
        DONE,
        CANCELLED,
        ERROR
    }

    public IndexRecord {
        Objects.requireNonNull(rootKey, "rootKey");
        Objects.requireNonNull(displayPath, "displayPath");
        Objects.requireNonNull(status, "status");
        if (message == null) {
            message = "";
        }
    }

    public IndexRecord withProgress(int folders, int files, int errors, long durationMs) {
        return new IndexRecord(rootKey, displayPath, Status.RUNNING, startedAt, 0, folders, files, durationMs, errors, message, depthLimit);
    }
}
