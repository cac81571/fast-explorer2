package com.fastexplorer.favorites;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** {@code ~/.fastexplorer/favorites.json}. Missing file means an empty tree. */
public final class FavoritesStore {
    private FavoritesStore() {
    }

    public static FavoriteTree load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return new FavoriteTree();
        }
        try {
            return FavoritesJson.read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException("お気に入りの形式が正しくありません: " + e.getMessage(), e);
        }
    }

    public static void save(Path file, FavoriteTree tree) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, FavoritesJson.write(tree), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
