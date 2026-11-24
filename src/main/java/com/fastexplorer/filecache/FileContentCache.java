package com.fastexplorer.filecache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Local copies of files, keyed by source size and last-modified time.
 * A matching copy is reused so a slow share is not read again.
 */
public final class FileContentCache implements AutoCloseable {
    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS file_copy (
                source_key VARCHAR(4096) PRIMARY KEY,
                relative_path VARCHAR(4096) NOT NULL,
                size BIGINT NOT NULL,
                modified_at BIGINT NOT NULL,
                last_used BIGINT NOT NULL,
                read_only BOOLEAN NOT NULL
            )
            """;

    private final Path root;
    private final long maxBytes;
    private final LongSupplier clock;
    private final Object lock = new Object();
    private Connection connection;

    private FileContentCache(Path root, long maxBytes, LongSupplier clock, String url) {
        this.root = root;
        this.maxBytes = maxBytes;
        this.clock = clock;
        try {
            Files.createDirectories(root);
            Class.forName("org.h2.Driver");
            this.connection = DriverManager.getConnection(url, "sa", "");
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCK_TIMEOUT 5000");
                statement.execute(SCHEMA);
            }
        } catch (IOException | SQLException | ClassNotFoundException e) {
            throw new IllegalStateException("ファイルキャッシュを開けません: " + root, e);
        }
    }

    public static FileContentCache open(Path root, long maxBytes) {
        Path database = root.resolve("index");
        String path = database.toAbsolutePath().normalize().toString().replace('\\', '/');
        return new FileContentCache(root, maxBytes, System::currentTimeMillis,
                "jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE");
    }

    public static FileContentCache inMemory(String name, Path root, long maxBytes, LongSupplier clock) {
        String safe = name.replaceAll("[^A-Za-z0-9_]", "_");
        return new FileContentCache(root, maxBytes, clock, "jdbc:h2:mem:" + safe + ";DB_CLOSE_DELAY=-1");
    }

    public Path root() {
        return root;
    }

    /**
     * Returns the local file when the recorded size and last-modified time still match
     * and the copy is on disk. Updates LRU either way when it is a hit.
     */
    public Path freshCopy(String sourcePath, long size, long modified, boolean readOnly) throws IOException {
        String key = FileCachePaths.sourceKey(sourcePath);
        Path dest = FileCachePaths.resolve(root, sourcePath);
        synchronized (lock) {
            Row row = findRow(key);
            if (row == null || row.size != size || row.modified != modified) {
                return null;
            }
            if (!Files.isRegularFile(dest) || Files.size(dest) != size) {
                return null;
            }
            touch(key, readOnly);
        }
        applyReadOnly(dest, readOnly);
        return dest;
    }

    public Path materialize(
            Path source,
            String sourcePath,
            long size,
            long modified,
            boolean readOnly,
            BooleanSupplier cancelled,
            Progress progress
    ) throws IOException {
        Path hit = freshCopy(sourcePath, size, modified, readOnly);
        if (hit != null) {
            return hit;
        }
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("コピーを中止しました");
        }
        Path dest = FileCachePaths.resolve(root, sourcePath);
        Files.createDirectories(dest.getParent());
        Path temp = dest.resolveSibling(dest.getFileName().toString() + ".part-" + UUID.randomUUID());
        boolean published = false;
        try {
            copy(source, temp, size, cancelled, progress);
            Files.setLastModifiedTime(temp, FileTime.fromMillis(Math.max(0, modified)));
            synchronized (lock) {
                if (Files.exists(dest)) {
                    dest.toFile().setWritable(true, false);
                }
                moveIntoPlace(temp, dest);
                published = true;
                Files.setLastModifiedTime(dest, FileTime.fromMillis(Math.max(0, modified)));
                applyReadOnly(dest, readOnly);
                String relative = root.normalize().relativize(dest.normalize()).toString().replace('\\', '/');
                upsert(FileCachePaths.sourceKey(sourcePath), relative, size, modified, readOnly);
                if (maxBytes > 0) {
                    evictTo(maxBytes, FileCachePaths.sourceKey(sourcePath));
                }
            }
            return dest;
        } catch (IOException | RuntimeException e) {
            if (!published) {
                Files.deleteIfExists(temp);
            }
            throw e;
        }
    }

    public long totalBytes() {
        synchronized (lock) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COALESCE(SUM(size), 0) FROM file_copy")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void evictTo(long limitBytes, String protectKey) {
        synchronized (lock) {
            List<Row> rows = listOldest();
            long total = 0;
            for (Row row : rows) {
                total += Math.max(0, row.size);
            }
            for (Row row : rows) {
                if (total <= limitBytes) {
                    break;
                }
                if (protectKey != null && protectKey.equals(row.key)) {
                    continue;
                }
                deleteCopy(row);
                total -= Math.max(0, row.size);
            }
        }
    }

    public void clear() throws IOException {
        synchronized (lock) {
            for (Row row : listOldest()) {
                deleteCopy(row);
            }
        }
        deleteTree(root.resolve("unc"));
        deleteTree(root.resolve("local"));
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Shutdown.
            } finally {
                connection = null;
            }
        }
    }

    private void copy(Path source, Path temp, long size, BooleanSupplier cancelled, Progress progress) throws IOException {
        long total = Math.max(0, size);
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[256 * 1024];
            long copied = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new CancellationException("コピーを中止しました");
                }
                out.write(buffer, 0, read);
                copied += read;
                if (progress != null) {
                    progress.bytesCopied(copied, total);
                }
            }
        }
    }

    private static void moveIntoPlace(Path temp, Path dest) throws IOException {
        try {
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void applyReadOnly(Path path, boolean readOnly) {
        if (readOnly) {
            path.toFile().setReadOnly();
        } else {
            path.toFile().setWritable(true, false);
        }
    }

    private void touch(String key, boolean readOnly) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE file_copy SET last_used = ?, read_only = ? WHERE source_key = ?")) {
            ps.setLong(1, clock.getAsLong());
            ps.setBoolean(2, readOnly);
            ps.setString(3, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void upsert(String key, String relative, long size, long modified, boolean readOnly) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM file_copy WHERE source_key = ?")) {
                delete.setString(1, key);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO file_copy (source_key, relative_path, size, modified_at, last_used, read_only)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, key);
                insert.setString(2, relative);
                insert.setLong(3, size);
                insert.setLong(4, modified);
                insert.setLong(5, clock.getAsLong());
                insert.setBoolean(6, readOnly);
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException(e);
        } finally {
            enableAutoCommit();
        }
    }

    private Row findRow(String key) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT source_key, relative_path, size, modified_at, last_used, read_only FROM file_copy WHERE source_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Row> listOldest() {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT source_key, relative_path, size, modified_at, last_used, read_only FROM file_copy ORDER BY last_used ASC")) {
            List<Row> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(readRow(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Row readRow(ResultSet rs) throws SQLException {
        return new Row(
                rs.getString(1),
                rs.getString(2),
                rs.getLong(3),
                rs.getLong(4),
                rs.getLong(5),
                rs.getBoolean(6));
    }

    private void deleteCopy(Row row) {
        try {
            Path file = root.resolve(row.relative.replace('/', java.io.File.separatorChar));
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The row is still removed so the index does not keep a dead file.
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM file_copy WHERE source_key = ?")) {
            ps.setString(1, row.key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void rollback() {
        try {
            if (connection != null) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // The original error is reported.
        }
    }

    private void enableAutoCommit() {
        try {
            if (connection != null) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // The next call reports a real failure.
        }
    }

    public interface Progress {
        void bytesCopied(long copied, long total);
    }

    private record Row(String key, String relative, long size, long modified, long lastUsed, boolean readOnly) {
    }

    static String readableSize(long bytes) {
        return String.format(Locale.US, "%d", bytes);
    }
}
