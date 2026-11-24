package com.fastexplorer.cache;

import com.fastexplorer.fs.NioDirectoryLister;
import com.fastexplorer.model.DirectorySnapshot;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.ListResult;
import com.fastexplorer.model.SearchHit;
import com.fastexplorer.search.ExtensionFilter;
import com.fastexplorer.search.NameFilter;
import com.fastexplorer.util.PathStrings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingCacheTest {
    @TempDir
    Path temp;

    @Test
    void roundTripsListingsAndReplacesOldNames() {
        try (ListingCache cache = ListingCache.inMemory("roundtrip")) {
            String key = "/data/share";
            cache.replace(key, List.of(file("a.txt", 4), dir("sub"), file("b.txt", 1)), 50, true);
            DirectorySnapshot snapshot = cache.find(key).orElseThrow();
            assertEquals(50, snapshot.listedAtEpochMillis());
            assertTrue(snapshot.complete());
            assertEquals(List.of("sub", "a.txt", "b.txt"), snapshot.entries().stream().map(FileEntry::name).toList());
            assertEquals(EntryKind.DIRECTORY, snapshot.entries().get(0).kind());
            assertEquals(-1, snapshot.entries().get(0).size());

            cache.replace(key, List.of(file("a.txt", 9)), 80, true);
            DirectorySnapshot replaced = cache.find(key).orElseThrow();
            assertEquals(List.of("a.txt"), replaced.entries().stream().map(FileEntry::name).toList());
            assertEquals(9, replaced.entries().get(0).size());
            assertEquals(80, replaced.listedAtEpochMillis());
            assertTrue(cache.searchByName(key, "b.txt", 10).isEmpty());
        }
    }

    @Test
    void searchIsCaseInsensitiveIndexedAndScopedToTheRoot() {
        try (ListingCache cache = ListingCache.inMemory("search")) {
            assertTrue(cache.hasNameIndex());
            String root = "/projects/alpha";
            String sibling = "/projects/alphabet";
            String child = "/projects/alpha/docs";
            cache.replace(root, List.of(file("ReadMe.TXT", 3), file("100%_off.txt", 2), dir("docs")), 1, true);
            cache.replace(child, List.of(file("readme-notes.txt", 8)), 1, true);
            cache.replace(sibling, List.of(file("ReadMe.TXT", 3)), 1, true);

            List<SearchHit> hits = cache.searchByName(root, "readme", 20);
            assertEquals(java.util.Set.of("ReadMe.TXT", "readme-notes.txt"),
                    hits.stream().map(SearchHit::name).collect(java.util.stream.Collectors.toSet()));
            assertEquals(child, hits.stream().filter(hit -> hit.name().equals("readme-notes.txt")).findFirst().orElseThrow().dirKey());

            List<SearchHit> literal = cache.searchByName(root, "100%_", 10);
            assertEquals(List.of("100%_off.txt"), literal.stream().map(SearchHit::name).toList());

            cache.replace(root, List.of(file("a'b.txt", 1)), 2, true);
            assertEquals("a'b.txt", cache.searchByName(root, "a'b", 5).get(0).name());
        }
    }

    @Test
    void emptyDirectoryIsACompleteSnapshot() {
        try (ListingCache cache = ListingCache.inMemory("empty")) {
            cache.replace("/empty", List.of(), 10, true);
            DirectorySnapshot snapshot = cache.find("/empty").orElseThrow();
            assertTrue(snapshot.complete());
            assertTrue(snapshot.entries().isEmpty());
            assertTrue(cache.find("/missing").isEmpty());
        }
    }

    @Test
    void fileDatabaseSurvivesReopen(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("cache");
        try (ListingCache cache = ListingCache.file(db)) {
            cache.replace("/keep", List.of(file("stay.txt", 6)), 7, true);
        }
        try (ListingCache reopened = ListingCache.file(db)) {
            assertEquals("stay.txt", reopened.find("/keep").orElseThrow().entries().get(0).name());
            assertEquals("stay.txt", reopened.searchByName("/keep", "stay", 5).get(0).name());
        }
    }

    @Test
    void tempDirectoryListingFeedsTheCache(@TempDir Path dir) throws Exception {
        Path folder = dir.resolve("share");
        Files.createDirectories(folder.resolve("sub"));
        Files.writeString(folder.resolve("notes.txt"), "abc");
        ListResult listed = new NioDirectoryLister().list(folder, () -> false, System.currentTimeMillis() + 10_000);
        String key = PathStrings.cacheKey(folder);
        try (ListingCache cache = ListingCache.file(dir.resolve("db"))) {
            cache.replace(key, listed.entries(), System.currentTimeMillis(), true);
            DirectorySnapshot snapshot = cache.find(key).orElseThrow();
            assertEquals(listed.entries(), snapshot.entries());
            assertEquals("notes.txt", cache.searchByName(key, "NOTES", 10).get(0).name());
        }
    }

    @Test
    void extensionFilterUsesTheIndexedColumn() {
        try (ListingCache cache = ListingCache.inMemory("ext" + System.nanoTime())) {
            assertTrue(cache.hasExtensionIndex());
            String root = "/share";
            cache.replace(root, java.util.List.of(
                    file("ReadMe.TXT", 3),
                    file("archive.tar.gz", 9),
                    file("Makefile", 1),
                    file(".gitignore", 1),
                    dir("readme-files")), 1, true);
            ExtensionFilter txt = ExtensionFilter.parse(".txt", false);
            assertEquals(java.util.List.of("ReadMe.TXT"),
                    cache.searchByName(root, "e", 20, txt).stream().map(SearchHit::name).toList());
            assertEquals(java.util.List.of("archive.tar.gz"),
                    cache.searchByName(root, "a", 20, ExtensionFilter.parse("tar.gz", false))
                            .stream().map(SearchHit::name).toList());
            ExtensionFilter withFolders = ExtensionFilter.parse("txt", true);
            assertEquals(java.util.Set.of("ReadMe.TXT", "readme-files"),
                    cache.searchByName(root, "readme", 20, withFolders).stream()
                            .map(SearchHit::name).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void migratesCachesCreatedBeforeTheExtensionColumn(@TempDir Path dir) throws Exception {
        Path database = dir.resolve("legacy");
        Class.forName("org.h2.Driver");
        String url = "jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url, "sa", "")) {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE file_entry (
                            dir_key VARCHAR(2048) NOT NULL,
                            name VARCHAR(512) NOT NULL,
                            name_lower VARCHAR(512) NOT NULL,
                            entry_kind VARCHAR(32) NOT NULL,
                            byte_size BIGINT NOT NULL,
                            modified_at BIGINT NOT NULL,
                            PRIMARY KEY (dir_key, name)
                        )
                        """);
                statement.execute("""
                        INSERT INTO file_entry (dir_key, name, name_lower, entry_kind, byte_size, modified_at)
                        VALUES ('/share', 'ReadMe.TXT', 'readme.txt', 'FILE', 4, 1),
                               ('/share', 'picture.PNG', 'picture.png', 'FILE', 8, 1),
                               ('/share', 'docs', 'docs', 'DIRECTORY', -1, 1)
                        """);
            }
        }
        try (ListingCache cache = ListingCache.file(database)) {
            assertTrue(cache.hasExtensionIndex());
            assertEquals(java.util.List.of("ReadMe.TXT"),
                    cache.searchByName("/share", "readme", 10, ExtensionFilter.parse("TXT", false))
                            .stream().map(SearchHit::name).toList());
            assertEquals(java.util.List.of("picture.PNG"),
                    cache.searchByName("/share", "pic", 10, ExtensionFilter.parse("png", false))
                            .stream().map(SearchHit::name).toList());
        }
    }

    @Test
    void directoryExcludeIsAppliedBeforeTheResultLimit() {
        try (ListingCache cache = ListingCache.inMemory("names" + System.nanoTime())) {
            cache.replace("/share", java.util.List.of(
                    file("zzz.txt", 1),
                    dir("node_modules")), 1, true);
            cache.replace("/share/node_modules", java.util.List.of(file("aaa.txt", 1)), 1, true);
            NameFilter excluded = NameFilter.parse("*.txt", "", "", "node_modules");
            assertEquals(java.util.List.of("zzz.txt"),
                    cache.search("/share", excluded, 1, ExtensionFilter.all()).stream()
                            .map(SearchHit::name).toList());
            cache.replace("/share", java.util.List.of(
                    file("zzz.txt", 1),
                    dir("node_modules"),
                    dir("src")), 1, true);
            cache.replace("/share/src", java.util.List.of(file("App.txt", 1), file("note.md", 1)), 1, true);
            NameFilter included = NameFilter.parse("", "", "src", "");
            assertEquals(java.util.Set.of("src", "App.txt", "note.md"),
                    cache.search("/share", included, 20, ExtensionFilter.all()).stream()
                            .map(SearchHit::name).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void longExtensionAndLongPathsSurviveANarrowLegacySchema(@TempDir Path dir) throws Exception {
        Path database = dir.resolve("legacy-widths");
        Class.forName("org.h2.Driver");
        String url = "jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url, "sa", "")) {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE dir_meta (
                            dir_key VARCHAR(2048) PRIMARY KEY,
                            listed_at BIGINT NOT NULL,
                            complete BOOLEAN NOT NULL,
                            entry_count INT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE file_entry (
                            dir_key VARCHAR(2048) NOT NULL,
                            name VARCHAR(512) NOT NULL,
                            name_lower VARCHAR(512) NOT NULL,
                            entry_kind VARCHAR(32) NOT NULL,
                            byte_size BIGINT NOT NULL,
                            modified_at BIGINT NOT NULL,
                            ext VARCHAR(64) DEFAULT '' NOT NULL,
                            PRIMARY KEY (dir_key, name)
                        )
                        """);
            }
        }
        String hash = "0-a82cb2897a8bf9445d68dcc2be05af89ad4b2fda1fddb2952693be7cd5353ad3";
        String hashedName = "widget." + hash;
        String longName = "n".repeat(600) + ".txt";
        String longDir = "/share/" + "p".repeat(4000);
        try (ListingCache cache = ListingCache.file(database)) {
            cache.replace(longDir, java.util.List.of(
                    file(hashedName, 4),
                    file(longName, 8),
                    file("ok.txt", 1)), 5, true);
            DirectorySnapshot snapshot = cache.find(longDir).orElseThrow();
            assertTrue(snapshot.complete());
            assertEquals(java.util.Set.of(hashedName, longName, "ok.txt"),
                    snapshot.entries().stream().map(FileEntry::name).collect(java.util.stream.Collectors.toSet()));
            assertEquals(java.util.List.of(longName),
                    cache.searchByName(longDir, "n".repeat(40), 10).stream().map(SearchHit::name).toList());
            assertEquals(java.util.List.of("ok.txt"),
                    cache.searchByName(longDir, "ok", 10, ExtensionFilter.parse("txt", false)).stream()
                            .map(SearchHit::name).toList());
            assertTrue(cache.searchByName(longDir, "widget", 10, ExtensionFilter.parse("txt", false)).isEmpty());
            assertEquals(java.util.List.of(hashedName),
                    cache.searchByName(longDir, "widget", 10).stream().map(SearchHit::name).toList());
        }
    }

    private static FileEntry file(String name, long size) {
        return new FileEntry(name, EntryKind.FILE, size, 1_700_000_000_000L);
    }

    private static FileEntry dir(String name) {
        return new FileEntry(name, EntryKind.DIRECTORY, -1, 1_700_000_000_000L);
    }
}
