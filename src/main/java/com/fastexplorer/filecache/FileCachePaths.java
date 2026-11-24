package com.fastexplorer.filecache;

import com.fastexplorer.util.PathStrings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps a source path, including UNC, onto a file inside the local cache root
 * so {@code \\server\share\a.txt} and {@code D:\a.txt} cannot overwrite each other.
 */
public final class FileCachePaths {
    private FileCachePaths() {
    }

    public static String sourceKey(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().replace('\\', '/');
        boolean unc = normalized.startsWith("//");
        while (normalized.contains("//")) {
            String collapsed = normalized.replace("//", "/");
            if (collapsed.equals(normalized)) {
                break;
            }
            normalized = collapsed;
        }
        if (unc && !normalized.startsWith("//")) {
            normalized = "/" + normalized;
        }
        if (PathStrings.isWindows()) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static Path resolve(Path cacheRoot, String sourcePath) {
        String key = sourceKey(sourcePath);
        boolean unc = key.startsWith("//");
        String rest = unc ? key.substring(2) : key;
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        rest = rest.replace(":", "");
        List<String> parts = new ArrayList<>();
        for (String part : rest.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException("キャッシュの外には出られません");
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(sanitize(part));
        }
        Path resolved = cacheRoot.resolve(unc ? "unc" : "local").normalize();
        for (String part : parts) {
            resolved = resolved.resolve(part).normalize();
        }
        if (!resolved.startsWith(cacheRoot.normalize())) {
            throw new IllegalArgumentException("キャッシュの外には出られません");
        }
        return resolved;
    }

    private static String sanitize(String part) {
        StringBuilder out = new StringBuilder(part.length());
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < 0x20 || "<>:\"|?*".indexOf(c) >= 0) {
                out.append('_');
            } else {
                out.append(c);
            }
        }
        String cleaned = out.toString();
        if (cleaned.isBlank() || cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.replaceAll("[. ]+$", "") + "_";
        }
        return cleaned;
    }
}
