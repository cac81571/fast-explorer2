package com.fastexplorer.ui;

import com.fastexplorer.model.EntryKind;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Two generic icons. Never resolved per file. */
public final class Icons {
    private static final Icon FOLDER = new ImageIcon(folder());
    private static final Icon FILE = new ImageIcon(file());
    private static final Icon LINK = new ImageIcon(link());
    private static final Icon GROUP = new ImageIcon(groupImage());

    private Icons() {
    }

    public static Icon forKind(EntryKind kind) {
        return switch (kind) {
            case DIRECTORY -> FOLDER;
            case SYMLINK -> LINK;
            case FILE -> FILE;
        };
    }

    public static Icon folderIcon() {
        return FOLDER;
    }

    public static Icon groupIcon() {
        return GROUP;
    }

    private static BufferedImage folder() {
        BufferedImage image = canvas();
        Graphics2D g = image.createGraphics();
        smooth(g);
        g.setColor(new Color(0xF0D48A));
        g.fillRoundRect(1, 2, 8, 4, 2, 2);
        g.setColor(new Color(0xE2B340));
        g.fillRoundRect(1, 5, 14, 9, 3, 3);
        g.dispose();
        return image;
    }

    private static BufferedImage groupImage() {
        BufferedImage image = canvas();
        Graphics2D g = image.createGraphics();
        smooth(g);
        g.setColor(new Color(0xC4A15A));
        g.fillRoundRect(0, 6, 10, 8, 2, 2);
        g.setColor(new Color(0xE2B340));
        g.fillRoundRect(4, 3, 11, 9, 2, 2);
        g.setColor(new Color(0xF0D48A));
        g.fillRect(4, 3, 6, 3);
        g.dispose();
        return image;
    }

    private static BufferedImage file() {
        BufferedImage image = canvas();
        Graphics2D g = image.createGraphics();
        smooth(g);
        g.setColor(new Color(0x4C84D6));
        g.fillRoundRect(3, 1, 10, 14, 2, 2);
        g.setColor(new Color(0xE8F0FB));
        g.fillPolygon(new int[]{8, 13, 8}, new int[]{1, 1, 6}, 3);
        g.dispose();
        return image;
    }

    private static BufferedImage link() {
        BufferedImage image = file();
        Graphics2D g = image.createGraphics();
        smooth(g);
        g.setColor(new Color(0x2F6F4E));
        g.setStroke(new BasicStroke(1.4f));
        g.drawArc(7, 8, 6, 6, 40, 280);
        g.dispose();
        return image;
    }

    private static BufferedImage canvas() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }
}
