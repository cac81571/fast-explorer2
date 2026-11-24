package com.fastexplorer.fs;

import com.fastexplorer.Messages;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.ListResult;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Directory listing via {@link Files#newDirectoryStream} only. No shell extensions, thumbnails,
 * property handlers, or per-file icon lookup.
 */
public final class NioDirectoryLister implements DirectoryLister {
    private final AttributeReader attributes;

    public NioDirectoryLister() {
        this(new AttributeReader());
    }

    public NioDirectoryLister(AttributeReader attributes) {
        this.attributes = attributes;
    }

    @Override
    public ListResult list(Path dir, BooleanSupplier cancelled, long deadlineEpochMillis) throws ListingException {
        if (cancelled.getAsBoolean()) {
            return new ListResult(List.of(), false, 0, 0);
        }
        if (System.currentTimeMillis() > deadlineEpochMillis) {
            throw new ListingException(ListingException.Kind.TIMEOUT, Messages.ERR_TIMEOUT);
        }
        List<FileEntry> entries = new ArrayList<>();
        int cacheHits = 0;
        int reads = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (cancelled.getAsBoolean()) {
                    return new ListResult(entries, false, cacheHits, reads);
                }
                if (System.currentTimeMillis() > deadlineEpochMillis) {
                    throw new ListingException(ListingException.Kind.TIMEOUT, Messages.ERR_TIMEOUT);
                }
                Path name = entry.getFileName();
                if (name == null) {
                    continue;
                }
                try {
                    AttributeReader.AttributeRead read = attributes.read(entry);
                    if (read.fromCache()) {
                        cacheHits++;
                    } else {
                        reads++;
                    }
                    entries.add(toEntry(name.toString(), read.attributes()));
                } catch (IOException perEntry) {
                    // One broken reparse point or ACL should not hide the rest of the folder.
                    entries.add(new FileEntry(name.toString(), EntryKind.FILE, -1L, 0L));
                    reads++;
                }
            }
        } catch (ListingException e) {
            throw e;
        } catch (Exception e) {
            throw map(e);
        }
        entries.sort(FileEntry.DISPLAY_ORDER);
        return new ListResult(entries, true, cacheHits, reads);
    }

    static FileEntry toEntry(String name, BasicFileAttributes attrs) {
        EntryKind kind;
        if (attrs.isDirectory()) {
            kind = EntryKind.DIRECTORY;
        } else if (attrs.isSymbolicLink()) {
            kind = EntryKind.SYMLINK;
        } else {
            kind = EntryKind.FILE;
        }
        long size = kind == EntryKind.FILE ? attrs.size() : -1L;
        FileTime modified = attrs.lastModifiedTime();
        long millis = modified == null ? 0L : modified.toMillis();
        return new FileEntry(name, kind, size, millis);
    }

    static ListingException map(Throwable e) {
        if (e instanceof ListingException listing) {
            return listing;
        }
        if (e instanceof DirectoryIteratorException iterator && iterator.getCause() != null) {
            return map(iterator.getCause());
        }
        if (e instanceof NoSuchFileException) {
            return new ListingException(ListingException.Kind.NOT_FOUND, Messages.ERR_NOT_FOUND, e);
        }
        if (e instanceof NotDirectoryException) {
            return new ListingException(ListingException.Kind.NOT_DIRECTORY, Messages.ERR_NOT_DIR, e);
        }
        if (e instanceof AccessDeniedException) {
            return new ListingException(ListingException.Kind.ACCESS_DENIED, Messages.ERR_DENIED, e);
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e instanceof FileSystemException && isUnreachable(msg)) {
            return new ListingException(ListingException.Kind.UNREACHABLE, Messages.ERR_UNREACHABLE, e);
        }
        if (isUnreachable(msg)) {
            return new ListingException(ListingException.Kind.UNREACHABLE, Messages.ERR_UNREACHABLE, e);
        }
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ListingException(ListingException.Kind.IO, Messages.ERR_IO + ": " + detail, e);
    }

    private static boolean isUnreachable(String lowerMessage) {
        return lowerMessage.contains("network path")
                || lowerMessage.contains("network name")
                || lowerMessage.contains("unreachable")
                || lowerMessage.contains("no route")
                || lowerMessage.contains("timed out")
                || lowerMessage.contains("timeout")
                || lowerMessage.contains("host is down")
                || lowerMessage.contains("connection refused");
    }
}
