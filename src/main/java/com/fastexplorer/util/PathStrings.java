package com.fastexplorer.util;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Cache keys and user-typed paths, including UNC paths such as {@code \\server\share\folder}.
 */
public final class PathStrings {
    private PathStrings() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isUnc(String raw) {
        if (raw == null) {
            return false;
        }
        return raw.startsWith("\\\\") || raw.startsWith("//");
    }

    public static String stripQuotes(String input) {
        String s = input == null ? "" : input.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /**
     * Parse an address-bar value. Relative paths resolve against {@code baseIfRelative}.
     * UNC strings are kept as typed so a Linux test run does not prefix the working directory.
     */
    public static Path parse(String input, Path baseIfRelative) {
        String s = stripQuotes(input);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        Path path = Path.of(s);
        if (!path.isAbsolute() && !isUnc(s) && baseIfRelative != null) {
            path = baseIfRelative.resolve(path);
        }
        return path.normalize();
    }

    public static String cacheKey(Path path) {
        String raw = path.normalize().toString();
        if (!isUnc(raw)) {
            raw = path.toAbsolutePath().normalize().toString();
        }
        return canonicalize(raw, isWindows());
    }

    /**
     * Forward slashes, no trailing slash (except a filesystem root). On Windows the key is lower-cased
     * so {@code D:\Data} and {@code d:\data} share one cache row.
     */
    public static String canonicalize(String absoluteOrUnc, boolean foldCase) {
        String s = absoluteOrUnc.replace('\\', '/');
        if (foldCase) {
            s = s.toLowerCase(Locale.ROOT);
        }
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static Path fromCacheKey(String cacheKey) {
        return Path.of(cacheKey);
    }

    public static String toDisplayPath(String cacheKey) {
        return toDisplayPath(cacheKey, isWindows());
    }

    public static String toDisplayPath(String cacheKey, boolean windows) {
        if (windows) {
            return cacheKey.replace('/', '\\');
        }
        return cacheKey;
    }

    public static String escapeLike(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String toContainsLikePattern(String query) {
        return "%" + escapeLike(query.toLowerCase(Locale.ROOT)) + "%";
    }

    public static boolean nameMatches(String name, String query) {
        return name.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    /** LIKE pattern that matches children of {@code rootKey} but not a sibling with a longer name. */
    public static String childPrefixLike(String rootKey) {
        return escapeLike(rootKey) + "/%";
    }
}
