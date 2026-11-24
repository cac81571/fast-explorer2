package com.fastexplorer.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListedPathsTest {
    @Test
    void relativePathUsesTheSearchRootAndKeepsTheFileName() {
        Path root = Path.of("D:/data");
        Path file = Path.of("D:/data/sub/dir/file.txt");
        assertEquals("sub\\dir\\file.txt", ListedPaths.relativeDisplay(root, file, true));
        assertEquals("sub/dir/file.txt", ListedPaths.relativeDisplay(root, file, false));
        assertEquals("file.txt", ListedPaths.relativeDisplay(root, Path.of("D:/data/file.txt"), true));
    }

    @Test
    void uncAndOutsidePaths() {
        Path root = Path.of("//server/share/docs");
        Path file = Path.of("//server/share/docs/sub/dir/file.txt");
        assertEquals("sub\\dir\\file.txt", ListedPaths.relativeDisplay(root, file, true));
        Path outside = Path.of("//other/share/a.txt");
        assertEquals(ListedPaths.fullDisplay(outside, true), ListedPaths.relativeDisplay(root, outside, true));
    }

    @Test
    void windowsMatchIgnoresCaseButKeepsTheTargetCasing() {
        Path root = Path.of("D:/Data");
        Path file = Path.of("D:/data/Sub/File.TXT");
        assertEquals("Sub\\File.TXT", ListedPaths.relativeDisplay(root, file, true));
    }

    @Test
    void fileAndFolderNames() {
        Path file = Path.of("D:/data/sub/dir/file.txt");
        Path folder = Path.of("D:/data/sub/dir");
        assertEquals("file.txt", ListedPaths.fileName(file));
        assertEquals("dir", ListedPaths.folderName(file, false));
        assertEquals("dir", ListedPaths.folderName(folder, true));
        assertEquals("/", ListedPaths.folderName(Path.of("/file.txt"), false));
    }

    @Test
    void joinUsesCrlfOnlyOnWindows() {
        assertEquals("a\r\nb", ListedPaths.joinLines(List.of("a", "b"), true));
        assertEquals("a\nb", ListedPaths.joinLines(List.of("a", "b"), false));
    }
}
