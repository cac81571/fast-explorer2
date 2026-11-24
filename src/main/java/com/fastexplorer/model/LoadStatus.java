package com.fastexplorer.model;

import java.util.Objects;

public record LoadStatus(int count, long elapsedMillis, Origin origin, String detail) {
    public enum Origin {
        CACHE,
        REFRESHING,
        FILESYSTEM,
        SEARCH
    }

    public LoadStatus {
        Objects.requireNonNull(origin, "origin");
        if (detail == null) {
            detail = "";
        }
    }
}
