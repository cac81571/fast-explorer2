package com.fastexplorer.favorites;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FavoritesJsonTest {
    @TempDir
    Path temp;

    @Test
    void roundTripsNestedNamesPathsAndOrder() throws Exception {
        FavoriteTree tree = new FavoriteTree();
        FavoriteNode group = tree.addGroup(null, "仕事 \"A\"");
        tree.addFolder(group.id(), "仕様書", "\\\\server\\share\\docs");
        tree.addFolder(null, "a\\b", "/tmp/a");
        FavoriteNode nested = tree.addGroup(group.id(), "2026");
        tree.addFolder(nested.id(), "案件", "\\\\fileserver\\share\\案件");

        String json = FavoritesJson.write(tree);
        FavoriteTree loaded = FavoritesJson.read(json);
        assertEquals(tree.roots().get(0).id(), loaded.roots().get(0).id());
        assertEquals("仕事 \"A\"", loaded.roots().get(0).name());
        assertEquals("\\\\server\\share\\docs", loaded.roots().get(0).children().get(0).path());
        assertEquals("2026", loaded.roots().get(0).children().get(1).name());
        assertEquals("案件", loaded.roots().get(0).children().get(1).children().get(0).name());
        assertEquals("a\\b", loaded.roots().get(1).name());

        Path file = temp.resolve("favorites.json");
        FavoritesStore.save(file, tree);
        FavoriteTree fromDisk = FavoritesStore.load(file);
        assertEquals("仕様書", fromDisk.roots().get(0).children().get(0).name());
        assertEquals(2, fromDisk.roots().size());
    }

    @Test
    void missingFileIsEmptyAndCorruptFileFails() throws Exception {
        assertTrue(FavoritesStore.load(temp.resolve("missing.json")).roots().isEmpty());
        Path broken = temp.resolve("broken.json");
        Files.writeString(broken, "{");
        assertThrows(java.io.IOException.class, () -> FavoritesStore.load(broken));
    }

    @Test
    void blankDocumentAndUnknownFieldsLoad() {
        FavoriteTree blank = FavoritesJson.read("   ");
        assertTrue(blank.roots().isEmpty());
        FavoriteTree extra = FavoritesJson.read("""
                {"extra": 1, "items": [{"id": "g", "name": "個人", "group": true, "children": []}]}
                """);
        assertEquals("個人", extra.roots().get(0).name());
        assertTrue(extra.roots().get(0).children().isEmpty());
    }
}
