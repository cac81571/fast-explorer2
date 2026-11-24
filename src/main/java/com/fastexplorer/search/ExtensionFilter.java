package com.fastexplorer.search;

import com.fastexplorer.model.EntryKind;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Case-insensitive filter on the last extension ({@code archive.tar.gz} is {@code gz}).
 * A leading-dot name such as {@code .gitignore} has no extension. An empty list means every file.
 * Folders are included or left out as a group; their names are not matched against the extension list.
 */
public final class ExtensionFilter {
    /**
     * Suffixes longer than this are not real extensions (for example a content hash after the last dot).
     * They are stored as “no extension” so one odd name cannot overflow the cache column.
     */
    public static final int MAX_STORED_EXTENSION = 64;

    private static final ExtensionFilter ALL = new ExtensionFilter(List.of(), true);

    private final List<String> extensions;
    private final boolean includeFolders;

    private ExtensionFilter(List<String> extensions, boolean includeFolders) {
        this.extensions = extensions;
        this.includeFolders = includeFolders;
    }

    public static ExtensionFilter all() {
        return ALL;
    }

    public static ExtensionFilter parse(String text, boolean includeFolders) {
        if (text == null || text.isBlank()) {
            return includeFolders ? ALL : new ExtensionFilter(List.of(), false);
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String token : text.split("[,;、\\s]+")) {
            String extension = normalizeToken(token);
            if (!extension.isEmpty()) {
                parsed.add(extension);
            }
        }
        if (parsed.isEmpty()) {
            return includeFolders ? ALL : new ExtensionFilter(List.of(), false);
        }
        return new ExtensionFilter(List.copyOf(parsed), includeFolders);
    }

    /**
     * Last extension without the dot, lowercased. Empty when the name has no extension,
     * or when the suffix is longer than {@link #MAX_STORED_EXTENSION}.
     */
    public static String extensionOf(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        String suffix = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (suffix.length() > MAX_STORED_EXTENSION) {
            return "";
        }
        return suffix;
    }

    public boolean includeFolders() {
        return includeFolders;
    }

    public boolean restrictsFiles() {
        return !extensions.isEmpty();
    }

    /** Insertion order, lowercased, without dots. */
    public List<String> extensions() {
        return extensions;
    }

    public boolean accepts(String name, EntryKind kind) {
        if (kind == EntryKind.DIRECTORY) {
            return includeFolders;
        }
        if (extensions.isEmpty()) {
            return true;
        }
        return extensions.contains(extensionOf(name));
    }

    /** Short Japanese status fragment, empty when the filter does not narrow results. */
    public String describe() {
        if (extensions.isEmpty() && includeFolders) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (!extensions.isEmpty()) {
            text.append("拡張子 ").append(String.join(", ", extensions));
        }
        if (!includeFolders) {
            if (!text.isEmpty()) {
                text.append(" · ");
            }
            text.append("フォルダなし");
        }
        return text.toString();
    }

    private static String normalizeToken(String token) {
        String trimmed = token.trim().toLowerCase(Locale.ROOT);
        while (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        int dot = trimmed.lastIndexOf('.');
        if (dot >= 0) {
            trimmed = trimmed.substring(dot + 1);
        }
        if (trimmed.isEmpty() || trimmed.length() > 32 || !isExtensionChar(trimmed)) {
            return "";
        }
        return trimmed;
    }

    private static boolean isExtensionChar(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '+' || c == '#')) {
                return false;
            }
        }
        return true;
    }

    /** Same order as {@link #extensions()}, safe to bind as SQL parameters. */
    public List<String> extensionsForQuery() {
        return extensions;
    }
}
