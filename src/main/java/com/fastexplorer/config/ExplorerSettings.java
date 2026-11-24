package com.fastexplorer.config;

public record ExplorerSettings(
        long ttlMillis,
        long timeoutMillis,
        int searchMaxDirectories,
        int searchMaxResults
) {
    public ExplorerSettings {
        if (ttlMillis < 0) {
            ttlMillis = 0;
        }
        if (timeoutMillis < 1) {
            timeoutMillis = 1;
        }
        if (searchMaxDirectories < 1) {
            searchMaxDirectories = 1;
        }
        if (searchMaxResults < 1) {
            searchMaxResults = 1;
        }
    }
}
