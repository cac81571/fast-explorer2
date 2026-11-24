package com.fastexplorer.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public final class Theme {
    private static final String[] FONT_CANDIDATES = {
            "Yu Gothic UI",
            "Meiryo UI",
            "Yu Gothic",
            "Meiryo",
            "Noto Sans CJK JP",
            "Noto Sans JP",
            "IPAexGothic",
            "WenQuanYi Micro Hei",
            "Droid Sans Fallback",
            "SansSerif"
    };

    private static Font baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);

    private Theme() {
    }

    public static void install(boolean dark) {
        baseFont = pickFont();
        UIManager.put("defaultFont", baseFont);
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        UIManager.put("defaultFont", baseFont);
    }

    public static Font baseFont() {
        return baseFont;
    }

    private static Font pickFont() {
        Set<String> available = Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : FONT_CANDIDATES) {
            if (available.contains(family)) {
                return new Font(family, Font.PLAIN, 13);
            }
        }
        String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.JAPANESE);
        if (names.length > 0 && Arrays.asList(names).contains("Dialog")) {
            return new Font("Dialog", Font.PLAIN, 13);
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }
}
