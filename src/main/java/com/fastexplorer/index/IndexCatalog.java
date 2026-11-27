package com.fastexplorer.index;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Per-root index metadata. Listings themselves stay in the directory cache. */
public final class IndexCatalog implements AutoCloseable {
    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS index_root (
                root_key VARCHAR(4096) PRIMARY KEY,
                display_path VARCHAR(4096) NOT NULL,
                status VARCHAR(16) NOT NULL,
                started_at BIGINT NOT NULL,
                finished_at BIGINT NOT NULL,
                folder_count INT NOT NULL,
                file_count INT NOT NULL,
                duration_ms BIGINT NOT NULL,
                error_count INT NOT NULL,
                message VARCHAR(1024) NOT NULL,
                depth_limit INT NOT NULL
            )
            """;

    private final Object lock = new Object();
    private Connection connection;

    private IndexCatalog(String url) {
        try {
            Class.forName("org.h2.Driver");
            this.connection = DriverManager.getConnection(url, "sa", "");
            try (Statement statement = connection.createStatement()) {
                statement.execute(SCHEMA);
            }
        } catch (SQLException | ClassNotFoundException e) {
            throw new IllegalStateException("インデックス情報を開けません", e);
        }
    }

    public static IndexCatalog open(java.nio.file.Path databaseFile) {
        java.nio.file.Path parent = databaseFile.getParent();
        if (parent != null) {
            try {
                java.nio.file.Files.createDirectories(parent);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        String path = databaseFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        return new IndexCatalog("jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE");
    }

    public static IndexCatalog inMemory(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9_]", "_");
        return new IndexCatalog("jdbc:h2:mem:" + safe + ";DB_CLOSE_DELAY=-1");
    }

    public void save(IndexRecord record) {
        synchronized (lock) {
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM index_root WHERE root_key = ?")) {
                    delete.setString(1, record.rootKey());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO index_root (
                            root_key, display_path, status, started_at, finished_at,
                            folder_count, file_count, duration_ms, error_count, message, depth_limit)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, record.rootKey());
                    insert.setString(2, record.displayPath());
                    insert.setString(3, record.status().name());
                    insert.setLong(4, record.startedAt());
                    insert.setLong(5, record.finishedAt());
                    insert.setInt(6, record.folderCount());
                    insert.setInt(7, record.fileCount());
                    insert.setLong(8, record.durationMs());
                    insert.setInt(9, record.errorCount());
                    insert.setString(10, record.message());
                    insert.setInt(11, record.depthLimit());
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
    }

    public Optional<IndexRecord> find(String rootKey) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM index_root WHERE root_key = ?")) {
                ps.setString(1, rootKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Best index that covers {@code pathKey}: an exact root or an ancestor.
     * Prefers the longest root key, then the newest finished time.
     */
    public Optional<IndexRecord> findCovering(String pathKey) {
        if (pathKey == null || pathKey.isBlank()) {
            return Optional.empty();
        }
        IndexRecord best = null;
        for (IndexRecord record : list()) {
            if (!covers(record.rootKey(), pathKey)) {
                continue;
            }
            if (best == null
                    || record.rootKey().length() > best.rootKey().length()
                    || (record.rootKey().length() == best.rootKey().length()
                    && createdAt(record) > createdAt(best))) {
                best = record;
            }
        }
        return Optional.ofNullable(best);
    }

    static boolean covers(String rootKey, String pathKey) {
        return pathKey.equals(rootKey) || pathKey.startsWith(rootKey + "/");
    }

    /** Creation / last-build time shown in the UI. */
    public static long createdAt(IndexRecord record) {
        if (record == null) {
            return 0;
        }
        if (record.finishedAt() > 0) {
            return record.finishedAt();
        }
        return record.startedAt();
    }

    public List<IndexRecord> list() {
        synchronized (lock) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("""
                         SELECT * FROM index_root
                         ORDER BY CASE status WHEN 'RUNNING' THEN 0 ELSE 1 END, started_at DESC
                         """)) {
                List<IndexRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(read(rs));
                }
                return rows;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void delete(String rootKey) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM index_root WHERE root_key = ?")) {
                ps.setString(1, rootKey);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static IndexRecord read(ResultSet rs) throws SQLException {
        IndexRecord.Status status;
        try {
            status = IndexRecord.Status.valueOf(rs.getString("status"));
        } catch (IllegalArgumentException e) {
            status = IndexRecord.Status.ERROR;
        }
        return new IndexRecord(
                rs.getString("root_key"),
                rs.getString("display_path"),
                status,
                rs.getLong("started_at"),
                rs.getLong("finished_at"),
                rs.getInt("folder_count"),
                rs.getInt("file_count"),
                rs.getLong("duration_ms"),
                rs.getInt("error_count"),
                rs.getString("message"),
                rs.getInt("depth_limit"));
    }

    private void rollback() {
        try {
            if (connection != null) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // The original error is reported by the caller.
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
}
