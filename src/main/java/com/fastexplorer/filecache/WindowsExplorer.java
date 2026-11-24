package com.fastexplorer.filecache;

import com.fastexplorer.util.PathStrings;

import java.io.IOException;
import java.util.List;

/** Builds {@code explorer.exe} commands. Launching is a no-op unless the OS is Windows. */
public final class WindowsExplorer {
    private WindowsExplorer() {
    }

    public static boolean available() {
        return PathStrings.isWindows();
    }

    public static List<String> openFolderCommand(String path) {
        return List.of("explorer.exe", path);
    }

    public static List<String> selectFileCommand(String path) {
        return List.of("explorer.exe", "/select," + path);
    }

    public static void openFolder(String path) throws IOException {
        launch(openFolderCommand(path));
    }

    public static void selectFile(String path) throws IOException {
        launch(selectFileCommand(path));
    }

    private static void launch(List<String> command) throws IOException {
        if (!available()) {
            throw new IOException("エクスプローラーは Windows でのみ使えます");
        }
        new ProcessBuilder(command).start();
    }
}
