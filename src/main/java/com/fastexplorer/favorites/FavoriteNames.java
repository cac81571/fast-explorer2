package com.fastexplorer.favorites;

/**
 * Display names for favorite folders. Splits on {@code /} and {@code \} so UNC paths keep the
 * last segment on every OS, without touching the network.
 */
public final class FavoriteNames {
    private FavoriteNames() {
    }

    public static String displayName(String pathText) {
        if (pathText == null) {
            return "";
        }
        String trimmed = pathText.trim();
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        if (slash >= 0 && slash < trimmed.length() - 1) {
            return trimmed.substring(slash + 1);
        }
        return trimmed.isEmpty() ? pathText.trim() : trimmed;
    }
}
