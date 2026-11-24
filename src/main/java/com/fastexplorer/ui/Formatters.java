package com.fastexplorer.ui;

import com.fastexplorer.Messages;
import com.fastexplorer.model.EntryKind;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Formatters {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private Formatters() {
    }

    public static String size(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        if (value >= 10 || Math.abs(value - Math.rint(value)) < 0.05) {
            return String.format(Locale.US, "%.0f %s", value, UNITS[unit]);
        }
        return String.format(Locale.US, "%.1f %s", value, UNITS[unit]);
    }

    public static String time(long epochMillis) {
        if (epochMillis <= 0) {
            return "";
        }
        return TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    public static String kind(EntryKind kind) {
        return switch (kind) {
            case DIRECTORY -> Messages.KIND_DIR;
            case FILE -> Messages.KIND_FILE;
            case SYMLINK -> Messages.KIND_LINK;
        };
    }
}
