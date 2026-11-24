package com.fastexplorer;

import com.fastexplorer.cache.CacheException;
import com.fastexplorer.cache.ListingCache;
import com.fastexplorer.config.AppConfig;
import com.fastexplorer.favorites.FavoriteTree;
import com.fastexplorer.favorites.FavoritesStore;
import com.fastexplorer.history.StartupFolder;
import com.fastexplorer.fs.AttributeReader;
import com.fastexplorer.ui.MainFrame;
import com.fastexplorer.ui.Theme;
import com.fastexplorer.util.PathStrings;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

public final class FastExplorerApp {
    private FastExplorerApp() {
    }

    public static void main(String[] args) {
        OptionalInt relaunched = relaunchOnWindowsIfNeeded(args);
        if (relaunched.isPresent()) {
            System.exit(relaunched.getAsInt());
        }
        Launch launch = Launch.parse(args);
        if (launch.help()) {
            System.out.println(help());
            return;
        }
        if (launch.selfCheck()) {
            System.exit(SelfCheck.run());
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(Messages.ERR_HEADLESS);
            System.exit(2);
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        AppConfig config = AppConfig.loadDefault();
        ListingCache cache = openCache(config);
        Path home = Path.of(System.getProperty("user.home"));
        Path start;
        if (launch.screenshotFile() != null && launch.startPath() == null) {
            start = demoDirectory();
        } else {
            String chosen = StartupFolder.select(
                    launch.startPath() == null ? "" : launch.startPath().toString(),
                    config.lastPath(),
                    config.initialPath());
            if (chosen.isBlank()) {
                start = home;
            } else {
                try {
                    start = PathStrings.parse(chosen, home);
                } catch (IllegalArgumentException e) {
                    start = home;
                }
            }
        }
        Path screenshot = launch.screenshotFile();
        Path initial = start;
        if (screenshot != null) {
            try {
                FavoritesStore.save(config.favoritesFile(), screenshotFavorites(initial));
                config.setFavoritesDivider(280);
                config.save();
            } catch (IOException e) {
                System.err.println(Messages.ERR_FAV_SAVE + ": " + e.getMessage());
            }
        }
        SwingUtilities.invokeLater(() -> {
            Theme.install(config.darkTheme());
            MainFrame frame = new MainFrame(config, cache, screenshot, () -> System.exit(0));
            frame.setVisible(true);
            frame.start(initial);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(cache::close, "fast-explorer-cache-close"));
    }

    private static ListingCache openCache(AppConfig config) {
        try {
            Files.createDirectories(config.dataDir());
            return ListingCache.file(config.databaseFile());
        } catch (CacheException | IOException e) {
            System.err.println(Messages.ERR_CACHE_FALLBACK + ": " + e.getMessage());
            return ListingCache.inMemory("fastexplorer_fallback");
        }
    }

    /**
     * Windows stores FindNextFile attributes on the Path. Reading them needs the JDK internal
     * package opened. A double-clicked jar has no flags, so relaunch once with them.
     */
    static OptionalInt relaunchOnWindowsIfNeeded(String[] args) {
        if (!PathStrings.isWindows() || Boolean.getBoolean("fastexplorer.relaunched")) {
            return OptionalInt.empty();
        }
        if (AttributeReader.cacheBridgeAvailable()) {
            return OptionalInt.empty();
        }
        Path jar = runningJar();
        if (jar == null) {
            return OptionalInt.empty();
        }
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        Path executable = System.console() == null && Files.exists(javaBin.resolve("javaw.exe"))
                ? javaBin.resolve("javaw.exe")
                : javaBin.resolve("java.exe");
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--add-opens");
        command.add("java.base/sun.nio.fs=ALL-UNNAMED");
        command.add("--add-exports");
        command.add("java.base/sun.nio.fs=ALL-UNNAMED");
        command.add("-Dfastexplorer.relaunched=true");
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(args));
        try {
            int code = new ProcessBuilder(command).inheritIO().start().waitFor();
            return OptionalInt.of(code);
        } catch (Exception e) {
            System.err.println("属性キャッシュ用の再起動に失敗したため、readAttributes で続行します: " + e.getMessage());
            return OptionalInt.empty();
        }
    }

    private static Path runningJar() {
        try {
            var source = FastExplorerApp.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return path;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static FavoriteTree screenshotFavorites(Path demo) {
        FavoriteTree tree = new FavoriteTree();
        var work = tree.addGroup(null, "仕事");
        tree.addFolder(work.id(), "仕様書", demo.resolve("仕様書").toString());
        tree.addFolder(work.id(), "見積", demo.resolve("見積").toString());
        var server = tree.addGroup(null, "サーバー");
        tree.addFolder(server.id(), "共有ドキュメント", "\\\\fileserver\\share\\docs");
        var year = tree.addGroup(server.id(), "2026年度");
        tree.addFolder(year.id(), "案件A", "\\\\fileserver\\share\\projects\\2026\\a");
        return tree;
    }

    static Path demoDirectory() {
        try {
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "fast-explorer-demo");
            Files.createDirectories(root.resolve("仕様書"));
            Files.createDirectories(root.resolve("見積"));
            Files.writeString(root.resolve("仕様書").resolve("要件.txt"), "Fast Explorer は名前と基本属性だけを読みます。\n");
            Files.writeString(root.resolve("見積").resolve("2026-11.txt"), "項目,金額\nライセンス,0\n");
            Files.writeString(root.resolve("メモ.txt"), "共有フォルダをすばやく一覧するためのツールです。\n");
            Files.writeString(root.resolve("売上.csv"), "月,金額\n4月,1200\n5月,980\n");
            Files.writeString(root.resolve("README.md"), "# サンプル\nこのフォルダは画面確認用です。\n");
            byte[] pdf = new byte[48 * 1024];
            Files.write(root.resolve("manual.pdf"), pdf);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String help() {
        return """
                Fast Explorer — 共有フォルダ向けの軽量ファイラー

                使い方:
                  java --add-opens java.base/sun.nio.fs=ALL-UNNAMED --add-exports java.base/sun.nio.fs=ALL-UNNAMED -jar fast-explorer.jar [パス]
                  java -jar fast-explorer.jar --self-check
                  java -jar fast-explorer.jar --screenshot 画像.png

                パスには \\\\server\\share\\folder や D:\\data を指定できます。
                設定ファイルは %USERPROFILE%\\.fastexplorer\\config.properties です。
                """;
    }

    record Launch(boolean selfCheck, boolean help, Path screenshotFile, Path startPath) {
        static Launch parse(String[] args) {
            boolean selfCheck = false;
            boolean help = false;
            Path screenshot = null;
            Path start = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--self-check" -> selfCheck = true;
                    case "--help", "-h", "/?" -> help = true;
                    case "--screenshot" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("--screenshot には出力ファイルが必要です");
                        }
                        screenshot = Path.of(args[++i]);
                    }
                    default -> {
                        if (arg.startsWith("--")) {
                            throw new IllegalArgumentException("不明なオプション: " + arg);
                        }
                        start = PathStrings.parse(arg, Path.of(System.getProperty("user.dir")));
                    }
                }
            }
            return new Launch(selfCheck, help, screenshot, start);
        }
    }
}
