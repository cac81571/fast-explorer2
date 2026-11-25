package com.fastexplorer.ui;

import com.fastexplorer.model.EntryKind;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/** Two generic icons. Never resolved per file. */
public final class Icons {
    private static final Icon FOLDER = new ImageIcon(folder());
    private static final Icon FILE = new ImageIcon(file());
    private static final Icon LINK = new ImageIcon(link());
    private static final Icon GROUP = new ImageIcon(groupImage());
    private static final List<Image> APP = List.of(
            appMark(16),
            appMark(32),
            appMark(48),
            appMark(64));

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

    /** Window / taskbar icons with the "FE" mark. */
    public static List<Image> appIcons() {
        return APP;
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

    private static BufferedImage appMark(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        smooth(g);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int arc = Math.max(3, size / 5);
        g.setColor(new Color(0x2F5FA8));
        g.fillRoundRect(0, 0, size, size, arc, arc);
        g.setColor(new Color(0x4C84D6));
        int inset = Math.max(1, size / 16);
        g.fillRoundRect(inset, inset, size - inset * 2, size - inset * 2, Math.max(2, arc - 1), Math.max(2, arc - 1));
        g.setColor(Color.WHITE);
        int fontSize = Math.max(8, Math.round(size * 0.52f));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        FontMetrics metrics = g.getFontMetrics();
        String mark = "FE";
        int x = (size - metrics.stringWidth(mark)) / 2;
        int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
        g.drawString(mark, x, y);
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
