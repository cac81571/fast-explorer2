package com.fastexplorer.ui;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicComboPopup;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/**
 * Editable combo whose dropdown is recent input. Typing completes from history.
 * Delete, or a right-click in the popup, removes one entry.
 */
public final class HistoryField extends JComboBox<String> {
    private final JTextField editor;
    private boolean adjusting;
    private boolean growHorizontally;
    private List<String> history = List.of();
    private Consumer<String> onDelete = value -> {
    };

    public HistoryField() {
        setEditable(true);
        setMaximumRowCount(12);
        editor = (JTextField) getEditor().getEditorComponent();
        editor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                completeFromHistory();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                // Deleting should not immediately refill the removed characters.
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                completeFromHistory();
            }
        });
        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_DELETE && isPopupVisible()) {
                    Object selected = getSelectedItem();
                    if (selected != null) {
                        onDelete.accept(selected.toString());
                        event.consume();
                    }
                }
            }
        });
        addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                SwingUtilities.invokeLater(HistoryField.this::hookPopupList);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
            }
        });
    }

    public void setHistory(List<String> values) {
        history = List.copyOf(values);
        adjusting = true;
        String current = editor.getText();
        removeAllItems();
        for (String value : history) {
            addItem(value);
        }
        editor.setText(current);
        adjusting = false;
    }

    public void setOnDelete(Consumer<String> onDelete) {
        this.onDelete = onDelete == null ? value -> {
        } : onDelete;
    }

    public void setGrowHorizontally(boolean growHorizontally) {
        this.growHorizontally = growHorizontally;
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension minimum = super.getMinimumSize();
        int width = growHorizontally ? 140 : 110;
        return new Dimension(Math.max(minimum.width, width), getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        if (growHorizontally) {
            return new Dimension(Integer.MAX_VALUE, preferred.height);
        }
        if (isMaximumSizeSet()) {
            return new Dimension(super.getMaximumSize().width, preferred.height);
        }
        return new Dimension(Math.min(280, Math.max(preferred.width, 160)), preferred.height);
    }

    public void setPlaceholder(String text) {
        // FlatLaf paints combo placeholders from the JComboBox client property.
        putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, text);
        editor.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, text);
    }

    public boolean isAdjusting() {
        return adjusting;
    }

    public String getText() {
        return editor.getText();
    }

    public void setText(String value) {
        adjusting = true;
        editor.setText(value == null ? "" : value);
        editor.setCaretPosition(editor.getText().length());
        adjusting = false;
    }

    public void setCaretPosition(int position) {
        editor.setCaretPosition(Math.min(Math.max(0, position), editor.getText().length()));
    }

    public void focusEditor(boolean selectAll) {
        editor.requestFocusInWindow();
        if (selectAll) {
            editor.selectAll();
        }
    }

    @Override
    public boolean isFocusOwner() {
        return super.isFocusOwner() || (editor != null && editor.isFocusOwner());
    }

    public javax.swing.text.Document getDocument() {
        return editor.getDocument();
    }

    private void completeFromHistory() {
        if (adjusting) {
            return;
        }
        String typed = editor.getText();
        if (typed.isEmpty()) {
            return;
        }
        for (String candidate : history) {
            if (candidate.length() > typed.length() && candidate.regionMatches(true, 0, typed, 0, typed.length())) {
                adjusting = true;
                editor.setText(candidate);
                editor.setCaretPosition(candidate.length());
                editor.moveCaretPosition(typed.length());
                adjusting = false;
                return;
            }
        }
    }

    private void hookPopupList() {
        if (!(getAccessibleContext().getAccessibleChild(0) instanceof BasicComboPopup popup)) {
            return;
        }
        JList<?> list = popup.getList();
        if (Boolean.TRUE.equals(list.getClientProperty("history-menu"))) {
            return;
        }
        list.putClientProperty("history-menu", Boolean.TRUE);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                showDeleteMenu(list, event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showDeleteMenu(list, event);
            }
        });
    }

    private void showDeleteMenu(JList<?> list, MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int index = list.locationToIndex(event.getPoint());
        if (index >= 0) {
            list.setSelectedIndex(index);
        }
        Object value = list.getSelectedValue();
        if (value == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem delete = new JMenuItem("この履歴を削除");
        delete.addActionListener(e -> onDelete.accept(value.toString()));
        menu.add(delete);
        menu.show(list, event.getX(), event.getY());
    }
}
