package com.fastexplorer;

import com.fastexplorer.cache.ListingCache;
import com.fastexplorer.favorites.FavoriteTree;
import com.fastexplorer.favorites.FavoritesStore;
import com.fastexplorer.fs.NioDirectoryLister;
import com.fastexplorer.model.ListResult;
import com.fastexplorer.model.SearchHit;
import com.fastexplorer.util.PathStrings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Headless smoke test: list a temp folder, cache it, and query the index. */
public final class SelfCheck {
    private SelfCheck() {
    }

    public static int run() {
        Path temp;
        try {
            temp = Files.createTempDirectory("fastexplorer-selfcheck");
        } catch (IOException e) {
            System.err.println("self-check: " + e.getMessage());
            return 1;
        }
        Path db = temp.resolve("db").resolve("cache");
        try {
            Files.createDirectories(db.getParent());
            Files.writeString(temp.resolve("alpha.txt"), "hello");
            Files.writeString(temp.resolve("日本語.txt"), "nihon");
            Files.createDirectories(temp.resolve("folder"));
            NioDirectoryLister lister = new NioDirectoryLister();
            ListResult listed = lister.list(temp, () -> false, System.currentTimeMillis() + 15_000);
            if (!listed.complete() || listed.entries().size() < 3) {
                System.err.println("self-check: listing returned " + listed.entries().size());
                return 1;
            }
            boolean sawFile = listed.entries().stream().anyMatch(e -> e.name().equals("alpha.txt") && e.size() == 5);
            boolean sawDir = listed.entries().stream().anyMatch(e -> e.name().equals("folder") && e.kind().name().equals("DIRECTORY"));
            if (!sawFile || !sawDir) {
                System.err.println("self-check: unexpected entries " + listed.entries());
                return 1;
            }
            String key = PathStrings.cacheKey(temp);
            try (ListingCache cache = ListingCache.file(db)) {
                cache.replace(key, listed.entries(), System.currentTimeMillis(), true);
                var again = cache.find(key);
                if (again.isEmpty() || again.get().entries().size() != listed.entries().size()) {
                    System.err.println("self-check: cache round-trip failed");
                    return 1;
                }
                List<SearchHit> hits = cache.searchByName(key, "alpha", 20);
                if (hits.size() != 1 || !hits.get(0).name().equals("alpha.txt")) {
                    System.err.println("self-check: search failed " + hits);
                    return 1;
                }
                if (!cache.hasNameIndex()) {
                    System.err.println("self-check: name index missing");
                    return 1;
                }
            }
            FavoriteTree favorites = new FavoriteTree();
            var group = favorites.addGroup(null, "仕事");
            favorites.addFolder(group.id(), "alpha", "\\\\server\\share\\alpha");
            Path favoritesFile = temp.resolve("favorites.json");
            FavoritesStore.save(favoritesFile, favorites);
            FavoriteTree loadedFavorites = FavoritesStore.load(favoritesFile);
            if (loadedFavorites.filtered("ALPHA").roots().isEmpty()) {
                System.err.println("self-check: favorites filter failed");
                return 1;
            }
            System.out.println("self-check: OK (entries=" + listed.entries().size() + ")");
            return 0;
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return 1;
        } finally {
            deleteTree(temp);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup of the temp self-check folder.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
