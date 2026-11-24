package com.fastexplorer.history;

/**
 * Which folder to open at startup, and whether a failed first open should leave the
 * cached view in place or fall back to home. The filesystem is not touched here.
 */
public final class StartupFolder {
    private StartupFolder() {
    }

    /**
     * Command line wins, then the folder that was open last time, then the configured initial path.
     * Blank means the caller should use the user home.
     */
    public static String select(String commandLine, String lastSaved, String configured) {
        if (hasText(commandLine)) {
            return commandLine.trim();
        }
        if (hasText(lastSaved)) {
            return lastSaved.trim();
        }
        if (hasText(configured)) {
            return configured.trim();
        }
        return "";
    }

    /** Fall back only when startup could not show anything, including a cached listing. */
    public static boolean shouldFallbackHome(boolean initialAttempt, boolean cacheVisible) {
        return initialAttempt && !cacheVisible;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
