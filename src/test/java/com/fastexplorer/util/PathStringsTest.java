package com.fastexplorer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathStringsTest {
    @TempDir
    Path temp;

    @Test
    void detectsUncAndStripsQuotes() {
        assertTrue(PathStrings.isUnc("\\\\server\\share\\folder"));
        assertTrue(PathStrings.isUnc("//server/share"));
        assertFalse(PathStrings.isUnc("D:\\data"));
        assertEquals("\\\\server\\share", PathStrings.stripQuotes("\"\\\\server\\share\""));
    }

    @Test
    void canonicalizeFoldsWindowsCaseAndSeparators() {
        assertEquals("//server/share/folder", PathStrings.canonicalize("\\\\server\\share\\folder\\", true));
        assertEquals("d:/data/プロジェクト", PathStrings.canonicalize("D:\\Data\\プロジェクト", true));
        assertEquals("/tmp/Data", PathStrings.canonicalize("/tmp/Data", false));
    }

    @Test
    void cacheKeyUsesAbsoluteLocalPath(@TempDir Path dir) {
        Path nested = dir.resolve("Child");
        String key = PathStrings.cacheKey(nested);
        assertFalse(key.contains("\\"));
        assertTrue(key.endsWith("/Child") || key.endsWith("/child"));
        assertEquals(key, PathStrings.cacheKey(nested));
    }

    @Test
    void relativePathResolvesAgainstBase() {
        Path resolved = PathStrings.parse("sub", temp);
        assertEquals(temp.resolve("sub").normalize(), resolved);
    }

    @Test
    void emptyPathIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathStrings.parse("   ", temp));
    }

    @Test
    void likePatternEscapesWildcards() {
        assertEquals("%100\\%\\_a\\\\b%", PathStrings.toContainsLikePattern("100%_a\\b"));
        assertTrue(PathStrings.nameMatches("ReadMe.TXT", "readme"));
        assertFalse(PathStrings.nameMatches("notes.txt", "readme"));
    }

    @Test
    void childPrefixDoesNotSwallowSiblings() {
        assertEquals("/data/foo/%", PathStrings.childPrefixLike("/data/foo"));
        assertEquals("/data/foo\\%/%", PathStrings.childPrefixLike("/data/foo%"));
    }

    @Test
    void displayPathUsesBackslashesOnWindowsRule() {
        assertEquals("\\\\server\\share\\a", PathStrings.toDisplayPath("//server/share/a", true));
        assertEquals("/tmp/a", PathStrings.toDisplayPath("/tmp/a", false));
    }
}
