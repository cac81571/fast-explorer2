package com.fastexplorer.ui;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Crisp chevron for the back, forward, and up buttons. Follows enabled and theme colors. */
public final class ArrowIcon implements Icon {
    public enum Dir { LEFT, RIGHT, UP }

    private final Dir direction;

    public ArrowIcon(Dir direction) {
        this.direction = direction;
    }

    @Override
    public int getIconWidth() {
        return 16;
    }

    @Override
    public int getIconHeight() {
        return 16;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(color(component));
            int x0 = x;
            int y0 = y;
            switch (direction) {
                case LEFT -> g.drawPolyline(new int[]{x0 + 10, x0 + 5, x0 + 10}, new int[]{y0 + 3, y0 + 8, y0 + 13}, 3);
                case RIGHT -> g.drawPolyline(new int[]{x0 + 6, x0 + 11, x0 + 6}, new int[]{y0 + 3, y0 + 8, y0 + 13}, 3);
                case UP -> g.drawPolyline(new int[]{x0 + 3, x0 + 8, x0 + 13}, new int[]{y0 + 10, y0 + 5, y0 + 10}, 3);
            }
        } finally {
            g.dispose();
        }
    }

    private static Color color(Component component) {
        boolean enabled = !(component instanceof AbstractButton button) || button.isEnabled();
        if (!enabled) {
            Color disabled = UIManager.getColor("Button.disabledText");
            if (disabled == null) {
                disabled = UIManager.getColor("Label.disabledForeground");
            }
            return disabled != null ? disabled : new Color(0xB0B0B0);
        }
        Color foreground = component.getForeground();
        return foreground != null ? foreground : new Color(0x2C2C2C);
    }
}
