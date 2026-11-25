package com.fastexplorer.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * {@code ~/.fastexplorer/config.properties}. Missing or unreadable values fall back to defaults.
 */
public final class AppConfig {
    public static final long DEFAULT_TTL_SECONDS = 120;
    public static final long DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_DIRECTORIES = 5000;
    public static final int DEFAULT_MAX_RESULTS = 5000;

    private final Path dataDir;
    private final Path configFile;
    private long cacheTtlSeconds = DEFAULT_TTL_SECONDS;
    private long listingTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private int searchMaxDirectories = DEFAULT_MAX_DIRECTORIES;
    private int searchMaxResults = DEFAULT_MAX_RESULTS;
    private boolean darkTheme;
    private String initialPath = "";
    private int favoritesDivider = 260;
    private final List<String> recentExtensions = new ArrayList<>();
    private String lastPath = "";
    private int historyMaxEntries = 50;
    private String fileCacheDir = "";
    private long fileCacheMaxMb = 2048;
    private int indexMaxDepth = 0;
    private boolean relativePaths = true;
    private boolean searchSubfolders = true;

    private AppConfig(Path dataDir) {
        this.dataDir = dataDir;
        this.configFile = dataDir.resolve("config.properties");
    }

    public static AppConfig loadDefault() {
        return load(Path.of(System.getProperty("user.home"), ".fastexplorer"));
    }

    public static AppConfig load(Path dataDir) {
        AppConfig config = new AppConfig(dataDir);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            // The cache open will report a usable error if the directory is really unavailable.
        }
        if (Files.isRegularFile(config.configFile)) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(config.configFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                return config;
            }
            config.cacheTtlSeconds = parseLong(properties, "cache.ttl.seconds", DEFAULT_TTL_SECONDS, 0, 86400L * 30);
            config.listingTimeoutSeconds = parseLong(
                    properties, "listing.timeout.seconds", DEFAULT_TIMEOUT_SECONDS, 1, 600);
            config.searchMaxDirectories = (int) parseLong(
                    properties, "search.max.directories", DEFAULT_MAX_DIRECTORIES, 1, 1_000_000);
            config.searchMaxResults = (int) parseLong(
                    properties, "search.max.results", DEFAULT_MAX_RESULTS, 1, 100_000);
            config.darkTheme = "dark".equalsIgnoreCase(properties.getProperty("theme", "light").trim());
            config.initialPath = properties.getProperty("initial.path", "").trim();
            config.favoritesDivider = (int) parseLong(properties, "favorites.divider", 260, 120, 800);
            config.recentExtensions.addAll(parseRecentExtensions(properties.getProperty("search.recent.extensions", "")));
            config.lastPath = properties.getProperty("last.path", "").trim();
            config.historyMaxEntries = (int) parseLong(properties, "history.max.entries", 50, 1, 500);
            config.fileCacheDir = properties.getProperty("filecache.dir", "").trim();
            config.fileCacheMaxMb = parseLong(properties, "filecache.max.mb", 2048, 1, 1_000_000);
            config.indexMaxDepth = (int) parseLong(properties, "index.max.depth", 0, 0, 1000);
            config.relativePaths = !"absolute".equalsIgnoreCase(
                    properties.getProperty("path.display", "relative").trim());
            config.searchSubfolders = !"false".equalsIgnoreCase(
                    properties.getProperty("search.subfolders", "true").trim());
        } else {
            try {
                config.save();
            } catch (IOException ignored) {
                // Running with defaults is still useful.
            }
        }
        return config;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path configFile() {
        return configFile;
    }

    public Path databaseFile() {
        return dataDir.resolve("cache");
    }

    public Path favoritesFile() {
        return dataDir.resolve("favorites.json");
    }

    public Path historyDatabaseFile() {
        return dataDir.resolve("history");
    }

    public Path indexDatabaseFile() {
        return dataDir.resolve("index-meta");
    }

    public Path fileCacheDirectory() {
        if (fileCacheDir.isBlank()) {
            return dataDir.resolve("filecache");
        }
        return Path.of(fileCacheDir);
    }

    public long fileCacheMaxBytes() {
        return fileCacheMaxMb * 1024L * 1024L;
    }

    public int historyMaxEntries() {
        return historyMaxEntries;
    }

    public int indexMaxDepth() {
        return indexMaxDepth;
    }

    public boolean relativePaths() {
        return relativePaths;
    }

    public void setRelativePaths(boolean relativePaths) {
        this.relativePaths = relativePaths;
    }

    public boolean searchSubfolders() {
        return searchSubfolders;
    }

    public void setSearchSubfolders(boolean searchSubfolders) {
        this.searchSubfolders = searchSubfolders;
    }

    public String lastPath() {
        return lastPath;
    }

    public void setLastPath(String lastPath) {
        this.lastPath = lastPath == null ? "" : lastPath.replace("\r", "").replace("\n", "").trim();
    }

    public int favoritesDivider() {
        return favoritesDivider;
    }

    public List<String> recentExtensions() {
        return List.copyOf(recentExtensions);
    }

    /** Remembers a non-empty extension box value, newest first, at most six entries. */
    public void rememberExtensions(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String trimmed = raw.trim();
        recentExtensions.removeIf(existing -> existing.equalsIgnoreCase(trimmed));
        recentExtensions.add(0, trimmed);
        while (recentExtensions.size() > 6) {
            recentExtensions.remove(recentExtensions.size() - 1);
        }
    }

    public void setFavoritesDivider(int favoritesDivider) {
        if (favoritesDivider < 120) {
            favoritesDivider = 120;
        } else if (favoritesDivider > 800) {
            favoritesDivider = 800;
        }
        this.favoritesDivider = favoritesDivider;
    }

    public boolean darkTheme() {
        return darkTheme;
    }

    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
    }

    public String initialPath() {
        return initialPath;
    }

    public ExplorerSettings settings() {
        return new ExplorerSettings(
                cacheTtlSeconds * 1000L,
                listingTimeoutSeconds * 1000L,
                searchMaxDirectories,
                searchMaxResults);
    }

    public void save() throws IOException {
        Files.createDirectories(dataDir);
        String safeInitial = initialPath.replace("\r", "").replace("\n", "");
        String text = """
                # Fast Explorer 設定
                # キャッシュの有効期限（秒）。期限内はキャッシュだけを表示します。
                # 0 にすると、キャッシュをすぐ表示したあと毎回ファイルシステムで更新します。
                cache.ttl.seconds=%d

                # フォルダ 1 つあたりのタイムアウト（秒）。応答しない共有で画面が止まり続けるのを防ぎます。
                listing.timeout.seconds=%d

                # 検索時に歩くフォルダ数の上限。現在のフォルダ以下が対象です。
                search.max.directories=%d

                # 検索結果の上限。
                search.max.results=%d

                # light または dark
                theme=%s

                # 起動時に開くパス。空ならユーザーホーム。コマンド引数があるときは引数が優先されます。
                initial.path=%s

                # お気に入りパネルと一覧の境目（ピクセル）
                favorites.divider=%d

                # 検索欄で使った拡張子。| 区切り。
                search.recent.extensions=%s

                # 前回開いていたフォルダ。コマンド引数があるときはそちらが優先されます。
                last.path=%s

                # アドレス・検索・拡張子の履歴の最大件数
                history.max.entries=%d

                # ローカルファイルキャッシュ。空なら設定フォルダの filecache
                filecache.dir=%s

                # ローカルファイルキャッシュの上限（MB）
                filecache.max.mb=%d

                # インデックス作成の深さ。0 は制限なし
                index.max.depth=%d

                # 検索結果の場所。relative（検索を始めたフォルダから）または absolute
                path.display=%s

                # サブフォルダも検索する
                search.subfolders=%s
                """.formatted(
                cacheTtlSeconds,
                listingTimeoutSeconds,
                searchMaxDirectories,
                searchMaxResults,
                darkTheme ? "dark" : "light",
                safeInitial,
                favoritesDivider,
                recentExtensions.stream().map(value -> value.replace("|", " ")).reduce((a, b) -> a + "|" + b).orElse(""),
                lastPath,
                historyMaxEntries,
                fileCacheDir,
                fileCacheMaxMb,
                indexMaxDepth,
                relativePaths ? "relative" : "absolute",
                searchSubfolders);
        try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
            writer.write(text);
        }
    }

    private static List<String> parseRecentExtensions(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : raw.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && values.stream().noneMatch(existing -> existing.equalsIgnoreCase(trimmed))) {
                values.add(trimmed);
            }
            if (values.size() == 6) {
                break;
            }
        }
        return values;
    }

    private static long parseLong(Properties properties, String key, long fallback, long min, long max) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
