package com.fastexplorer.history;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupFolderTest {
    @Test
    void commandLineOverridesLastFolderAndConfig() {
        assertEquals("\\\\server\\now", StartupFolder.select("\\\\server\\now", "D:\\last", "D:\\initial"));
        assertEquals("D:\\last", StartupFolder.select("  ", "D:\\last", "D:\\initial"));
        assertEquals("D:\\initial", StartupFolder.select(null, "", "D:\\initial"));
        assertEquals("", StartupFolder.select(null, " ", " "));
    }

    @Test
    void fallbackHomeOnlyWhenStartupHasNothingToShow() {
        assertTrue(StartupFolder.shouldFallbackHome(true, false));
        assertFalse(StartupFolder.shouldFallbackHome(true, true));
        assertFalse(StartupFolder.shouldFallbackHome(false, false));
    }
}
