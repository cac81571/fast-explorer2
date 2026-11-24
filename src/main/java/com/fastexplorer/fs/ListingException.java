package com.fastexplorer.fs;

/**
 * A directory listing failed in a way the UI can explain. Cancellation is not shown as an error.
 */
public final class ListingException extends Exception {
    public enum Kind {
        NOT_FOUND,
        NOT_DIRECTORY,
        ACCESS_DENIED,
        TIMEOUT,
        UNREACHABLE,
        IO,
        CANCELLED
    }

    private final Kind kind;

    public ListingException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ListingException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
