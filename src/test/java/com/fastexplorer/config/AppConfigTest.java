package com.fastexplorer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {
    @TempDir
    Path temp;

    @Test
    void writesDefaultsAndReadsThemBack() throws Exception {
        AppConfig created = AppConfig.load(temp.resolve("cfg"));
        assertTrue(Files.isRegularFile(created.configFile()));
        assertEquals(120, created.settings().ttlMillis() / 1000);
        created.setDarkTheme(true);
        created.save();

        AppConfig loaded = AppConfig.load(created.dataDir());
        assertTrue(loaded.darkTheme());
        assertEquals(30_000, loaded.settings().timeoutMillis());
        String text = Files.readString(created.configFile(), StandardCharsets.UTF_8);
        assertTrue(text.contains("cache.ttl.seconds=120"));
        assertTrue(text.contains("theme=dark"));
        assertEquals(260, loaded.favoritesDivider());
        loaded.setFavoritesDivider(40);
        assertEquals(120, loaded.favoritesDivider());
    }

    @Test
    void illegalValuesFallBackOrClamp() throws Exception {
        Path dir = temp.resolve("bad");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.properties"), """
                cache.ttl.seconds=nope
                listing.timeout.seconds=0
                search.max.directories=99999999
                theme=dark
                initial.path=\\\\\\\\server\\\\share
                """, StandardCharsets.UTF_8);
        AppConfig config = AppConfig.load(dir);
        assertEquals(AppConfig.DEFAULT_TTL_SECONDS * 1000, config.settings().ttlMillis());
        assertEquals(1_000, config.settings().timeoutMillis());
        assertEquals(1_000_000, config.settings().searchMaxDirectories());
        assertTrue(config.darkTheme());
        assertFalse(config.initialPath().isBlank());
    }

    @Test
    void roundTripsWindowsLastPathWithBackslashes() throws Exception {
        AppConfig created = AppConfig.load(temp.resolve("paths"));
        String windowsPath = "C:\\Users\\n-sug.INSPIRON5515\\Desktop\\share";
        created.setLastPath(windowsPath);
        created.save();

        String raw = Files.readString(created.configFile(), StandardCharsets.UTF_8);
        assertTrue(raw.contains("last.path=C:\\\\Users\\\\n-sug.INSPIRON5515\\\\Desktop\\\\share"));

        AppConfig loaded = AppConfig.load(created.dataDir());
        assertEquals(windowsPath, loaded.lastPath());
    }

    @Test
    void escapePropertyDoublesBackslashes() {
        assertEquals("", AppConfig.escapeProperty(null));
        assertEquals("C:\\\\Users\\\\foo", AppConfig.escapeProperty("C:\\Users\\foo"));
    }

    @Test
    void roundTripsExternalEditorPath() throws Exception {
        AppConfig created = AppConfig.load(temp.resolve("editor"));
        String editor = "C:\\Program Files\\Microsoft VS Code\\Code.exe";
        created.setExternalEditor(editor);
        created.save();

        String raw = Files.readString(created.configFile(), StandardCharsets.UTF_8);
        assertTrue(raw.contains("external.editor=C:\\\\Program Files\\\\Microsoft VS Code\\\\Code.exe"));

        AppConfig loaded = AppConfig.load(created.dataDir());
        assertEquals(editor, loaded.externalEditor());
    }
}
