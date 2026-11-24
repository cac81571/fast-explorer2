package com.fastexplorer.fs;

import com.fastexplorer.model.ListResult;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

public interface DirectoryLister {
    /**
     * List {@code dir} with a single {@code DirectoryStream} pass.
     * Stop when {@code cancelled} becomes true or {@code deadlineEpochMillis} is in the past.
     */
    ListResult list(Path dir, BooleanSupplier cancelled, long deadlineEpochMillis) throws ListingException;
}
