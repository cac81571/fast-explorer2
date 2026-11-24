package com.fastexplorer.history;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recent address, search, and extension values. Newest first, case-insensitive duplicates collapse.
 */
public final class InputHistory implements AutoCloseable {
    public enum Bucket {
        PATH, SEARCH, EXTENSION, INCLUDE_FILE, EXCLUDE_FILE, INCLUDE_DIR, EXCLUDE_DIR
    }

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS input_history (
                bucket VARCHAR(32) NOT NULL,
                entry_value VARCHAR(4096) NOT NULL,
                used_at BIGINT NOT NULL,
                PRIMARY KEY (bucket, entry_value)
            )
            """;

    private final int maxEntries;
    private final Object lock = new Object();
    private Connection connection;

    private InputHistory(String url, int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        try {
            Class.forName("org.h2.Driver");
            this.connection = DriverManager.getConnection(url, "sa", "");
            try (Statement statement = connection.createStatement()) {
                statement.execute(SCHEMA);
            }
        } catch (SQLException | ClassNotFoundException e) {
            throw new IllegalStateException("入力履歴を開けません", e);
        }
    }

    public static InputHistory open(java.nio.file.Path databaseFile, int maxEntries) {
        java.nio.file.Path parent = databaseFile.getParent();
        if (parent != null) {
            try {
                java.nio.file.Files.createDirectories(parent);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        String path = databaseFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        return new InputHistory("jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE", maxEntries);
    }

    public static InputHistory inMemory(String name, int maxEntries) {
        String safe = name.replaceAll("[^A-Za-z0-9_]", "_");
        return new InputHistory("jdbc:h2:mem:" + safe + ";DB_CLOSE_DELAY=-1", maxEntries);
    }

    public void remember(Bucket bucket, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        synchronized (lock) {
            try {
                connection.setAutoCommit(false);
                for (String existing : list(bucket)) {
                    if (existing.equalsIgnoreCase(trimmed)) {
                        deleteExact(bucket, existing);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO input_history (bucket, entry_value, used_at) VALUES (?, ?, ?)")) {
                    insert.setString(1, bucket.name());
                    insert.setString(2, trimmed);
                    insert.setLong(3, System.nanoTime());
                    insert.executeUpdate();
                }
                trim(bucket);
                connection.commit();
            } catch (SQLException e) {
                rollback();
                throw new IllegalStateException(e);
            } finally {
                enableAutoCommit();
            }
        }
    }

    public List<String> list(Bucket bucket) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT entry_value FROM input_history WHERE bucket = ? ORDER BY used_at DESC")) {
                ps.setString(1, bucket.name());
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> values = new ArrayList<>();
                    while (rs.next()) {
                        values.add(rs.getString(1));
                    }
                    return values;
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public boolean delete(Bucket bucket, String value) {
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            for (String existing : list(bucket)) {
                if (existing.equalsIgnoreCase(value.trim())) {
                    deleteExact(bucket, existing);
                    return true;
                }
            }
            return false;
        }
    }

    public void clear(Bucket bucket) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM input_history WHERE bucket = ?")) {
                ps.setString(1, bucket.name());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void clearAll() {
        synchronized (lock) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM input_history");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void trim(Bucket bucket) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT entry_value FROM input_history WHERE bucket = ? ORDER BY used_at DESC")) {
            ps.setString(1, bucket.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
        }
        for (int i = maxEntries; i < values.size(); i++) {
            deleteExact(bucket, values.get(i));
        }
    }

    private void deleteExact(Bucket bucket, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM input_history WHERE bucket = ? AND entry_value = ?")) {
            ps.setString(1, bucket.name());
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
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

    static String fold(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
