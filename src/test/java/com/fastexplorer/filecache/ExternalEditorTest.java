package com.fastexplorer.filecache;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalEditorTest {
    @Test
    void buildsCommandWithEditorAndFile() {
        assertEquals(
                List.of("C:\\Program Files\\Editor\\edit.exe", "\\\\server\\share\\a.txt"),
                ExternalEditor.command("C:\\Program Files\\Editor\\edit.exe", "\\\\server\\share\\a.txt"));
        assertTrue(ExternalEditor.isConfigured("  notepad.exe "));
        assertFalse(ExternalEditor.isConfigured(" "));
        assertFalse(ExternalEditor.isConfigured(null));
    }

    @Test
    void rejectsBlankEditorOrFile() {
        assertThrows(IllegalArgumentException.class, () -> ExternalEditor.command("", "a.txt"));
        assertThrows(IllegalArgumentException.class, () -> ExternalEditor.command("edit.exe", " "));
    }
}
