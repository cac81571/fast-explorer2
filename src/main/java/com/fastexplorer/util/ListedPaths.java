package com.fastexplorer.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Display and clipboard text for a listed file. Open and copy actions keep the absolute path;
 * the table can show the path relative to the folder that was searched.
 */
public final class ListedPaths {
    private ListedPaths() {
    }

    public static String fullDisplay(Path path) {
        String slashed = path.toString().replace('\\', '/');
        return PathStrings.toDisplayPath(slashed, PathStrings.isWindows());
    }

    public static String fullDisplay(Path path, boolean windows) {
        String slashed = path.toString().replace('\\', '/');
        return PathStrings.toDisplayPath(slashed, windows);
    }

    /**
     * Path of {@code target} relative to {@code root}, using backslashes when {@code windows} is set.
     * A target outside {@code root} stays absolute.
     */
    public static String relativeDisplay(Path root, Path target, boolean windows) {
        if (root == null || target == null) {
            return target == null ? "" : fullDisplay(target, windows);
        }
        String rootSlashed = stripTrailingSlash(root.toString().replace('\\', '/'));
        String targetSlashed = stripTrailingSlash(target.toString().replace('\\', '/'));
        String rootCompare = windows ? rootSlashed.toLowerCase(Locale.ROOT) : rootSlashed;
        String targetCompare = windows ? targetSlashed.toLowerCase(Locale.ROOT) : targetSlashed;
        String relative;
        if (targetCompare.equals(rootCompare)) {
            relative = fileName(target);
        } else {
            String prefix = rootCompare.endsWith("/") ? rootCompare : rootCompare + "/";
            if (!targetCompare.startsWith(prefix)) {
                return fullDisplay(target, windows);
            }
            relative = targetSlashed.substring(prefix.length());
        }
        return windows ? relative.replace('/', '\\') : relative.replace('\\', '/');
    }

    public static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /** A folder's own name, or the parent folder name of a file. */
    public static String folderName(Path path, boolean directory) {
        if (directory) {
            return fileName(path);
        }
        Path parent = path.getParent();
        if (parent == null) {
            return "";
        }
        Path name = parent.getFileName();
        return name == null ? parent.toString() : name.toString();
    }

    /**
     * Relative folder path under {@code root}: for a file, the parent path with the file name
     * removed; for a directory, the directory's own relative path. Empty when the folder is
     * {@code root} itself.
     */
    public static String relativeFolderPath(Path root, Path target, boolean directory, boolean windows) {
        if (target == null) {
            return "";
        }
        Path folder = directory ? target : target.getParent();
        if (folder == null) {
            return "";
        }
        if (root == null) {
            return fullDisplay(folder, windows);
        }
        String rootSlashed = stripTrailingSlash(root.toString().replace('\\', '/'));
        String folderSlashed = stripTrailingSlash(folder.toString().replace('\\', '/'));
        String rootCompare = windows ? rootSlashed.toLowerCase(Locale.ROOT) : rootSlashed;
        String folderCompare = windows ? folderSlashed.toLowerCase(Locale.ROOT) : folderSlashed;
        if (folderCompare.equals(rootCompare)) {
            return "";
        }
        return relativeDisplay(root, folder, windows);
    }

    public static String joinLines(List<String> lines, boolean windows) {
        return String.join(windows ? "\r\n" : "\n", lines);
    }

    private static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
