package com.fastexplorer.filecache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Launches a user-configured editor with a file path. The editor string is the executable
 * path; the file is passed as a single argument (ProcessBuilder handles spaces).
 */
public final class ExternalEditor {
    private ExternalEditor() {
    }

    public static boolean isConfigured(String editor) {
        return editor != null && !editor.isBlank();
    }

    public static List<String> command(String editor, String filePath) {
        if (!isConfigured(editor)) {
            throw new IllegalArgumentException("外部エディタが設定されていません");
        }
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("ファイルパスが空です");
        }
        List<String> command = new ArrayList<>(2);
        command.add(editor.trim());
        command.add(filePath);
        return command;
    }

    public static void open(String editor, Path file) throws IOException {
        List<String> command = command(editor, file.toString());
        String executable = command.get(0);
        Path asPath = Path.of(executable);
        boolean looksAbsolute = asPath.isAbsolute()
                || executable.indexOf('/') >= 0
                || executable.indexOf('\\') >= 0;
        if (looksAbsolute && !Files.isRegularFile(asPath)) {
            throw new IOException("外部エディタが見つかりません: " + executable);
        }
        new ProcessBuilder(command).start();
    }
}
