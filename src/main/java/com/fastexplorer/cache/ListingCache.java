package com.fastexplorer.cache;

import com.fastexplorer.model.DirectorySnapshot;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.SearchHit;
import com.fastexplorer.search.ExtensionFilter;
import com.fastexplorer.search.NameFilter;
import com.fastexplorer.util.PathStrings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded H2 cache of directory listings. File names are indexed in {@code name_lower}
 * so a search can answer {@code LIKE} from the database before walking the filesystem.
 */
public final class ListingCache implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("fastexplorer");
    /** H2's maximum character-varying length. Paths and names are not capped below this. */
    private static final int UNBOUNDED = 1_000_000_000;
    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS dir_meta (
                dir_key CHARACTER VARYING(1000000000) PRIMARY KEY,
                listed_at BIGINT NOT NULL,
                complete BOOLEAN NOT NULL,
                entry_count INT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS file_entry (
                dir_key CHARACTER VARYING(1000000000) NOT NULL,
                name CHARACTER VARYING(1000000000) NOT NULL,
                name_lower CHARACTER VARYING(1000000000) NOT NULL,
                entry_kind VARCHAR(32) NOT NULL,
                byte_size BIGINT NOT NULL,
                modified_at BIGINT NOT NULL,
                ext CHARACTER VARYING(1000000000) DEFAULT '' NOT NULL,
                PRIMARY KEY (dir_key, name)
            );
            CREATE INDEX IF NOT EXISTS idx_file_entry_name_lower ON file_entry (name_lower);
            """;

    private final String url;
    private final String description;
    private final Object lock = new Object();
    private Connection connection;

    private ListingCache(String url, String description) {
        this.url = url;
        this.description = description;
        try {
            Class.forName("org.h2.Driver");
            this.connection = DriverManager.getConnection(url, "sa", "");
            try (Statement st = connection.createStatement()) {
                st.execute("SET LOCK_TIMEOUT 5000");
                for (String sql : SCHEMA.split(";")) {
                    String trimmed = sql.strip();
                    if (!trimmed.isEmpty()) {
                        st.execute(trimmed);
                    }
                }
                migrateExtensionColumn();
                widenTextColumns();
            }
        } catch (SQLException | ClassNotFoundException e) {
            throw new CacheException("キャッシュ DB を開けません: " + description, e);
        }
    }

    public static ListingCache file(Path databaseFile) {
        String path = databaseFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE";
        return new ListingCache(url, path);
    }

    public static ListingCache inMemory(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9_]", "_");
        String url = "jdbc:h2:mem:" + safe + ";DB_CLOSE_DELAY=-1";
        return new ListingCache(url, "メモリ");
    }

    public String description() {
        return description;
    }

    public Optional<DirectorySnapshot> find(String dirKey) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT listed_at, complete FROM dir_meta WHERE dir_key = ?")) {
                ps.setString(1, dirKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    long listedAt = rs.getLong(1);
                    boolean complete = rs.getBoolean(2);
                    return Optional.of(new DirectorySnapshot(dirKey, listedAt, complete, loadEntries(dirKey)));
                }
            } catch (SQLException e) {
                throw new CacheException("キャッシュの読み取りに失敗しました", e);
            }
        }
    }

    public void replace(String dirKey, List<FileEntry> entries, long listedAtEpochMillis, boolean complete) {
        synchronized (lock) {
            try {
                connection.setAutoCommit(false);
                deleteChildren(dirKey);
                int stored = insertEntries(dirKey, entries);
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM dir_meta WHERE dir_key = ?")) {
                    del.setString(1, dirKey);
                    del.executeUpdate();
                }
                try (PreparedStatement meta = connection.prepareStatement(
                        "INSERT INTO dir_meta (dir_key, listed_at, complete, entry_count) VALUES (?, ?, ?, ?)")) {
                    meta.setString(1, dirKey);
                    meta.setLong(2, listedAtEpochMillis);
                    meta.setBoolean(3, complete && stored == entries.size());
                    meta.setInt(4, stored);
                    meta.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new CacheException("キャッシュの書き込みに失敗しました", e);
            } finally {
                enableAutoCommit();
            }
        }
    }

    /** Removes cached listings for {@code rootKey} and every directory under it. */
    public int deleteUnder(String rootKey) {
        String childLike = PathStrings.childPrefixLike(rootKey);
        synchronized (lock) {
            try {
                int removed = deleteMatching("file_entry", rootKey, childLike);
                removed += deleteMatching("dir_meta", rootKey, childLike);
                return removed;
            } catch (SQLException e) {
                throw new CacheException("キャッシュの削除に失敗しました", e);
            }
        }
    }

    private int deleteMatching(String table, String rootKey, String childLike) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE dir_key = ? OR dir_key LIKE ? ESCAPE '\\'")) {
            ps.setString(1, rootKey);
            ps.setString(2, childLike);
            return ps.executeUpdate();
        }
    }

    /**
     * Indexed {@code name_lower LIKE} restricted to {@code rootKey} and its descendants.
     * A sibling such as {@code /data2} is not returned for root {@code /data}.
     */
    public List<SearchHit> searchByName(String rootKey, String query, int limit) {
        return searchByName(rootKey, query, limit, ExtensionFilter.all());
    }

    /**
     * Indexed {@code name_lower LIKE} plus an optional {@code ext IN (...)} filter.
     * {@code ext} is the lowercased last extension, so the index can reject other types
     * before the filesystem is walked.
     */
    public List<SearchHit> searchByName(String rootKey, String query, int limit, ExtensionFilter filter) {
        return searchByName(rootKey, query, limit, filter, true);
    }

    public List<SearchHit> searchByName(
            String rootKey,
            String query,
            int limit,
            ExtensionFilter filter,
            boolean includeSubfolders
    ) {
        ExtensionFilter effective = filter == null ? ExtensionFilter.all() : filter;
        int capped = Math.max(1, Math.min(limit, 100_000));
        String pattern = PathStrings.toContainsLikePattern(query);
        String childLike = PathStrings.childPrefixLike(rootKey);
        List<String> extensions = effective.extensionsForQuery();
        StringBuilder sql = new StringBuilder("""
                SELECT dir_key, name, entry_kind, byte_size, modified_at
                FROM file_entry
                WHERE name_lower LIKE ? ESCAPE '\\'
                """);
        if (includeSubfolders) {
            sql.append(" AND (dir_key = ? OR dir_key LIKE ? ESCAPE '\\')");
        } else {
            sql.append(" AND dir_key = ?");
        }
        if (!extensions.isEmpty()) {
            sql.append(" AND (entry_kind <> 'DIRECTORY' AND ext IN (");
            for (int i = 0; i < extensions.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('?');
            }
            sql.append(')');
            if (effective.includeFolders()) {
                sql.append(" OR entry_kind = 'DIRECTORY'");
            }
            sql.append(')');
        } else if (!effective.includeFolders()) {
            sql.append(" AND entry_kind <> 'DIRECTORY'");
        }
        sql.append(" ORDER BY name_lower, dir_key LIMIT ?");
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                int index = 1;
                ps.setString(index++, pattern);
                ps.setString(index++, rootKey);
                if (includeSubfolders) {
                    ps.setString(index++, childLike);
                }
                for (String extension : extensions) {
                    ps.setString(index++, extension);
                }
                ps.setInt(index, capped);
                return readHits(ps, rootKey, null);
            } catch (SQLException e) {
                throw new CacheException("キャッシュ検索に失敗しました", e);
            }
        }
    }

    /**
     * Indexed name search plus file and directory include/exclude patterns.
     * Directory patterns are applied in SQL so {@code LIMIT} does not drop a later match
     * in favor of an excluded folder such as {@code node_modules}.
     */
    public List<SearchHit> search(String rootKey, NameFilter names, int limit, ExtensionFilter filter) {
        return search(rootKey, names, limit, filter, true);
    }

    public List<SearchHit> search(
            String rootKey,
            NameFilter names,
            int limit,
            ExtensionFilter filter,
            boolean includeSubfolders
    ) {
        NameFilter effectiveNames = names == null ? NameFilter.all() : names;
        ExtensionFilter effective = filter == null ? ExtensionFilter.all() : filter;
        int capped = Math.max(1, Math.min(limit, 100_000));
        String childLike = PathStrings.childPrefixLike(rootKey);
        List<String> extensions = effective.extensionsForQuery();
        List<String> includeLikes = effectiveNames.fileIncludeLikes();
        List<String> excludeLikes = effectiveNames.fileExcludeLikes();
        String includeDir = effectiveNames.directoryIncludeRegex();
        String excludeDir = effectiveNames.directoryExcludeRegex();
        StringBuilder sql = new StringBuilder("""
                SELECT dir_key, name, entry_kind, byte_size, modified_at
                FROM file_entry
                WHERE 
                """);
        if (includeSubfolders) {
            sql.append("(dir_key = ? OR dir_key LIKE ? ESCAPE '\\')");
        } else {
            sql.append("dir_key = ?");
        }
        if (!includeLikes.isEmpty() || !excludeLikes.isEmpty()) {
            sql.append(" AND (entry_kind = 'DIRECTORY' OR (");
            if (includeLikes.isEmpty()) {
                sql.append("1 = 1");
            } else {
                sql.append('(');
                for (int i = 0; i < includeLikes.size(); i++) {
                    if (i > 0) {
                        sql.append(" OR ");
                    }
                    sql.append("name_lower LIKE ? ESCAPE '\\'");
                }
                sql.append(')');
            }
            for (int i = 0; i < excludeLikes.size(); i++) {
                sql.append(" AND name_lower NOT LIKE ? ESCAPE '\\'");
            }
            sql.append("))");
        }
        appendDirectorySql(sql, includeDir, excludeDir);
        if (!extensions.isEmpty()) {
            sql.append(" AND (entry_kind <> 'DIRECTORY' AND ext IN (");
            for (int i = 0; i < extensions.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('?');
            }
            sql.append(')');
            if (effective.includeFolders()) {
                sql.append(" OR entry_kind = 'DIRECTORY'");
            }
            sql.append(')');
        } else if (!effective.includeFolders()) {
            sql.append(" AND entry_kind <> 'DIRECTORY'");
        }
        sql.append(" ORDER BY name_lower, dir_key LIMIT ?");
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                int index = 1;
                ps.setString(index++, rootKey);
                if (includeSubfolders) {
                    ps.setString(index++, childLike);
                }
                for (String like : includeLikes) {
                    ps.setString(index++, like);
                }
                for (String like : excludeLikes) {
                    ps.setString(index++, like);
                }
                if (!includeDir.isEmpty()) {
                    index = bindRelative(ps, index, rootKey);
                    ps.setString(index++, includeDir);
                }
                if (!excludeDir.isEmpty()) {
                    index = bindRelative(ps, index, rootKey);
                    ps.setString(index++, excludeDir);
                }
                for (String extension : extensions) {
                    ps.setString(index++, extension);
                }
                ps.setInt(index, capped);
                return readHits(ps, rootKey, effectiveNames);
            } catch (SQLException e) {
                throw new CacheException("キャッシュ検索に失敗しました", e);
            }
        }
    }

    private static void appendDirectorySql(StringBuilder sql, String includeDir, String excludeDir) {
        if (!includeDir.isEmpty()) {
            sql.append(" AND (").append(relativePathSql()).append(" REGEXP ?)");
        }
        if (!excludeDir.isEmpty()) {
            sql.append(" AND NOT (").append(relativePathSql()).append(" REGEXP ?)");
        }
    }

    /**
     * Relative path of the row from the bound root key, without a leading slash.
     * Directory rows include their own name; file rows use the parent directory.
     */
    private static String relativePathSql() {
        String suffix = "SUBSTRING(LOWER(dir_key), CHAR_LENGTH(?) + 1)";
        return """
                LOWER(CASE
                    WHEN entry_kind = 'DIRECTORY' AND CHAR_LENGTH(%1$s) = 0 THEN name_lower
                    WHEN entry_kind = 'DIRECTORY' THEN SUBSTRING(%1$s, 2) || '/' || name_lower
                    WHEN CHAR_LENGTH(%1$s) = 0 THEN ''
                    ELSE SUBSTRING(%1$s, 2)
                END)
                """.formatted(suffix);
    }

    private static int bindRelative(PreparedStatement ps, int index, String rootKey) throws SQLException {
        ps.setString(index++, rootKey);
        ps.setString(index++, rootKey);
        ps.setString(index++, rootKey);
        ps.setString(index++, rootKey);
        return index;
    }

    private List<SearchHit> readHits(PreparedStatement ps, String rootKey, NameFilter names) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<SearchHit> hits = new ArrayList<>();
            while (rs.next()) {
                SearchHit hit = new SearchHit(
                        rs.getString(1),
                        rs.getString(2),
                        parseKind(rs.getString(3)),
                        rs.getLong(4),
                        rs.getLong(5));
                if (names != null && !names.accepts(NameFilter.relative(rootKey, hit.dirKey()), hit.name(), hit.kind())) {
                    continue;
                }
                hits.add(hit);
            }
            return hits;
        }
    }

    public boolean hasNameIndex() {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'FILE_ENTRY'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        if (name != null && name.toLowerCase(Locale.ROOT).contains("name_lower")) {
                            return true;
                        }
                    }
                    return false;
                }
            } catch (SQLException e) {
                throw new CacheException("インデックス情報を読めません", e);
            }
        }
    }

    public boolean hasExtensionIndex() {
        return indexNamed("ext");
    }

    private boolean indexNamed(String fragment) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'FILE_ENTRY'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        if (name != null && name.toLowerCase(Locale.ROOT).contains(fragment)) {
                            return true;
                        }
                    }
                    return false;
                }
            } catch (SQLException e) {
                throw new CacheException("インデックス情報を読めません", e);
            }
        }
    }

    private List<FileEntry> loadEntries(String dirKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, entry_kind, byte_size, modified_at FROM file_entry WHERE dir_key = ?")) {
            ps.setString(1, dirKey);
            try (ResultSet rs = ps.executeQuery()) {
                List<FileEntry> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(new FileEntry(
                            rs.getString(1),
                            parseKind(rs.getString(2)),
                            rs.getLong(3),
                            rs.getLong(4)));
                }
                entries.sort(FileEntry.DISPLAY_ORDER);
                return entries;
            }
        }
    }

    private void deleteChildren(String dirKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM file_entry WHERE dir_key = ?")) {
            ps.setString(1, dirKey);
            ps.executeUpdate();
        }
    }

    /**
     * Writes every row it can. A single value that the database still rejects is skipped
     * so the rest of the directory is cached.
     */
    private int insertEntries(String dirKey, List<FileEntry> entries) throws SQLException {
        String sql = """
                INSERT INTO file_entry (dir_key, name, name_lower, entry_kind, byte_size, modified_at, ext)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        java.sql.Savepoint batchPoint = connection.setSavepoint();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int batched = 0;
            for (FileEntry entry : entries) {
                if (!bindEntry(ps, dirKey, entry)) {
                    continue;
                }
                ps.addBatch();
                if (++batched % 400 == 0) {
                    ps.executeBatch();
                }
            }
            if (batched > 0 && batched % 400 != 0) {
                ps.executeBatch();
            }
            return batched;
        } catch (SQLException batchFailure) {
            connection.rollback(batchPoint);
            LOG.log(Level.WARNING, "directory cache batch failed; storing rows individually", batchFailure);
        }
        int stored = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (FileEntry entry : entries) {
                java.sql.Savepoint row = connection.setSavepoint();
                try {
                    if (!bindEntry(ps, dirKey, entry)) {
                        continue;
                    }
                    ps.executeUpdate();
                    stored++;
                } catch (SQLException rowFailure) {
                    connection.rollback(row);
                    LOG.log(Level.WARNING, "skipped cache row " + entry.name(), rowFailure);
                }
            }
        }
        return stored;
    }

    private static boolean bindEntry(PreparedStatement ps, String dirKey, FileEntry entry) throws SQLException {
        if (entry.name() == null || entry.name().isBlank() || entry.kind() == null) {
            return false;
        }
        ps.setString(1, dirKey);
        ps.setString(2, entry.name());
        ps.setString(3, entry.name().toLowerCase(Locale.ROOT));
        ps.setString(4, entry.kind().name());
        ps.setLong(5, entry.size());
        ps.setLong(6, entry.modifiedEpochMillis());
        ps.setString(7, ExtensionFilter.extensionOf(entry.name()));
        return true;
    }

    /**
     * Databases created before the extension column get {@code ext} plus an index,
     * and existing rows are filled from the file name.
     */
    private void migrateExtensionColumn() throws SQLException {
        boolean added = false;
        if (!columnExists("EXT")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE file_entry ADD COLUMN ext VARCHAR(64) DEFAULT '' NOT NULL");
            }
            added = true;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS idx_file_entry_ext ON file_entry (ext)");
        }
        if (added) {
            backfillExtensions();
        }
    }

    /** Older caches capped paths at 2048, names at 512, and extensions at 64. */
    private void widenTextColumns() throws SQLException {
        widen("FILE_ENTRY", "file_entry", "DIR_KEY");
        widen("FILE_ENTRY", "file_entry", "NAME");
        widen("FILE_ENTRY", "file_entry", "NAME_LOWER");
        widen("FILE_ENTRY", "file_entry", "EXT");
        widen("DIR_META", "dir_meta", "DIR_KEY");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS idx_file_entry_name_lower ON file_entry (name_lower)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_file_entry_ext ON file_entry (ext)");
        }
    }

    private void widen(String schemaTable, String table, String column) throws SQLException {
        Long maximum = characterMaximum(schemaTable, column);
        if (maximum != null && maximum >= UNBOUNDED) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ALTER COLUMN " + column
                    + " SET DATA TYPE CHARACTER VARYING(" + UNBOUNDED + ")");
        }
    }

    private Long characterMaximum(String table, String column) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getObject(1) == null) {
                    return null;
                }
                return rs.getLong(1);
            }
        }
    }

    private boolean columnExists(String column) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'FILE_ENTRY' AND COLUMN_NAME = ?
                """)) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private void backfillExtensions() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT dir_key, name FROM file_entry")) {
            while (rs.next()) {
                rows.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE file_entry SET ext = ? WHERE dir_key = ? AND name = ?")) {
            int batched = 0;
            for (String[] row : rows) {
                ps.setString(1, ExtensionFilter.extensionOf(row[1]));
                ps.setString(2, row[0]);
                ps.setString(3, row[1]);
                ps.addBatch();
                if (++batched % 400 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw e;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    private static EntryKind parseKind(String raw) {
        if (raw == null) {
            return EntryKind.FILE;
        }
        try {
            return EntryKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return EntryKind.FILE;
        }
    }

    private void rollbackQuietly() {
        try {
            if (connection != null) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    private void enableAutoCommit() {
        try {
            if (connection != null) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // Leave the connection as-is; the next call reports a real error if it is unusable.
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
                // Closing during shutdown.
            } finally {
                connection = null;
            }
        }
    }
}
