package com.fastexplorer.ui;

import com.fastexplorer.Messages;
import com.fastexplorer.model.EntryKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormattersTest {
    @Test
    void formatsSizeAndKind() {
        assertEquals("—", Formatters.size(-1));
        assertEquals("0 B", Formatters.size(0));
        assertEquals("1023 B", Formatters.size(1023));
        assertEquals("1 KB", Formatters.size(1024));
        assertEquals("1.5 KB", Formatters.size(1536));
        assertEquals("2 MB", Formatters.size(2L * 1024 * 1024));
        assertEquals(Messages.KIND_DIR, Formatters.kind(EntryKind.DIRECTORY));
        assertEquals(Messages.KIND_FILE, Formatters.kind(EntryKind.FILE));
        assertEquals(Messages.KIND_LINK, Formatters.kind(EntryKind.SYMLINK));
    }

    @Test
    void formatsLocalTime() {
        long epoch = Instant.parse("2026-11-24T01:02:00Z").toEpochMilli();
        String expected = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epoch));
        assertEquals(expected, Formatters.time(epoch));
        assertEquals("", Formatters.time(0));
    }
}
