package com.fastexplorer.ui;

import com.fastexplorer.Messages;
import com.fastexplorer.favorites.FavoriteNames;
import com.fastexplorer.favorites.FavoriteNode;
import com.fastexplorer.favorites.FavoriteTree;
import com.fastexplorer.favorites.FavoritesStore;
import com.fastexplorer.history.InputHistory;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Left-hand favorites tree. Clicks open a path on the caller thread only long enough to hand it
 * to {@code openPath}; the caller must not block on the filesystem.
 */
public final class FavoritesPanel extends JPanel {
    private static final Logger LOG = Logger.getLogger("fastexplorer");
    static final DataFlavor FAVORITE_FLAVOR = new DataFlavor(String.class, "fast-explorer-favorite");

    private final FavoriteTree tree;
    private final Path file;
    private final Consumer<String> openPath;
    private final Consumer<String> onError;
    private final InputHistory inputHistory;
    private final HistoryField filter = new HistoryField();
    private final JTree view = new JTree();
    private final FavoriteRenderer renderer = new FavoriteRenderer();

    public FavoritesPanel(
            FavoriteTree tree,
            Path file,
            Consumer<String> openPath,
            Consumer<String> onError,
            InputHistory inputHistory
    ) {
        super(new BorderLayout(0, 6));
        this.tree = tree;
        this.file = file;
        this.openPath = openPath;
        this.onError = onError;
        this.inputHistory = inputHistory;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        setMinimumSize(new Dimension(180, 120));
        setPreferredSize(new Dimension(260, 400));
        add(header(), BorderLayout.NORTH);
        view.setRootVisible(false);
        view.setShowsRootHandles(true);
        view.setRowHeight(26);
        view.setCellRenderer(renderer);
        applyTreeSelectionStyle();
        view.setDragEnabled(true);
        view.setDropMode(DropMode.ON_OR_INSERT);
        view.setTransferHandler(new FavoriteTransfer());
        view.setName("favoritesTree");
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                TreePath path = pathAt(event.getX(), event.getY());
                if (path != null) {
                    view.setSelectionPath(path);
                }
                showPopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showPopup(event);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.isPopupTrigger() || !SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                FavoriteNode node = nodeAt(event.getX(), event.getY());
                if (node != null && !node.isGroup()) {
                    openPath.accept(node.path());
                }
            }
        });
        view.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-favorite");
        view.getActionMap().put("open-favorite", action(this::openSelected));
        view.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename-favorite");
        view.getActionMap().put("rename-favorite", action(this::renameSelected));
        view.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete-favorite");
        view.getActionMap().put("delete-favorite", action(this::deleteSelected));
        add(new JScrollPane(view), BorderLayout.CENTER);
        reload();
    }

    public void addCurrent(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        addPath(path, selectedGroupId());
    }

    public void addFromList(String path) {
        addPath(path, selectedGroupId());
    }

    public void addPath(String path, String parentGroupId) {
        if (path == null || path.isBlank()) {
            return;
        }
        FavoriteNode added = tree.addFolder(parentGroupId, FavoriteNames.displayName(path), path.trim());
        if (added == null && parentGroupId != null) {
            added = tree.addFolder(null, FavoriteNames.displayName(path), path.trim());
        }
        if (added == null) {
            onError.accept(Messages.ERR_FAV_MOVE);
            return;
        }
        persist();
        reload();
        select(added.id());
    }

    public void exportFavorites() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("favorites.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        try {
            FavoritesStore.save(target, tree);
        } catch (IOException e) {
            onError.accept(Messages.ERR_FAV_SAVE + ": " + e.getMessage());
        }
    }

    public void importFavorites() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(
                this,
                Messages.FAV_IMPORT_CONFIRM,
                Messages.FAVORITES,
                JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            tree.replaceWith(FavoritesStore.load(chooser.getSelectedFile().toPath()));
            persist();
            reload();
        } catch (IOException e) {
            onError.accept(Messages.ERR_FAV_LOAD + ": " + e.getMessage());
        }
    }

    public void restyle() {
        view.setRowHeight(26);
        view.setCellRenderer(renderer);
        applyTreeSelectionStyle();
    }

    public void clearFilterHistory() {
        filter.setHistory(List.of());
    }

    private void rememberFilter() {
        inputHistory.remember(InputHistory.Bucket.FAVORITES_FILTER, filter.getText());
        filter.setHistory(inputHistory.list(InputHistory.Bucket.FAVORITES_FILTER));
    }

    private void applyTreeSelectionStyle() {
        view.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
        // Keep the focused (blue) selection look even after focus moves to the file table.
        java.util.Map<String, Object> style = new java.util.HashMap<>();
        java.awt.Color background = javax.swing.UIManager.getColor("Tree.selectionBackground");
        java.awt.Color foreground = javax.swing.UIManager.getColor("Tree.selectionForeground");
        if (background != null) {
            style.put("selectionInactiveBackground", background);
        }
        if (foreground != null) {
            style.put("selectionInactiveForeground", foreground);
        }
        view.putClientProperty(FlatClientProperties.STYLE, style);
    }

    private JPanel header() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(Messages.FAVORITES);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        filter.setGrowHorizontally(true);
        filter.setPlaceholder(Messages.FAV_FILTER);
        filter.setToolTipText(Messages.TIP_FAV_FILTER);
        filter.setName("favoritesFilter");
        filter.setAlignmentX(LEFT_ALIGNMENT);
        filter.setMaximumSize(new Dimension(Integer.MAX_VALUE, filter.getPreferredSize().height));
        filter.setHistory(inputHistory.list(InputHistory.Bucket.FAVORITES_FILTER));
        filter.setOnDelete(value -> {
            inputHistory.delete(InputHistory.Bucket.FAVORITES_FILTER, value);
            filter.setHistory(inputHistory.list(InputHistory.Bucket.FAVORITES_FILTER));
        });
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                reload();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                reload();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                reload();
            }
        });
        filter.addActionListener(event -> {
            if (SearchTriggers.enterInClosedField(
                    event, filter.isAdjusting(), filter.isPopupVisible(), filter.consumeEnterCommit())) {
                rememberFilter();
            } else if (!filter.isAdjusting() && "comboBoxChanged".equals(event.getActionCommand())) {
                rememberFilter();
            }
        });
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        buttons.setOpaque(false);
        JButton add = new JButton(Messages.FAV_ADD_CURRENT);
        add.setToolTipText(Messages.TIP_FAV_ADD);
        add.setFocusable(false);
        add.addActionListener(event -> addCurrentListener());
        JButton group = new JButton(Messages.FAV_ADD_GROUP);
        group.setToolTipText(Messages.TIP_FAV_GROUP);
        group.setFocusable(false);
        group.addActionListener(event -> createGroup());
        JButton collapse = new JButton(Messages.FAV_COLLAPSE_ALL);
        collapse.setToolTipText(Messages.TIP_FAV_COLLAPSE_ALL);
        collapse.setFocusable(false);
        collapse.setName("favoritesCollapseAll");
        collapse.addActionListener(event -> collapseAll());
        buttons.add(add);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(group);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(collapse);
        buttons.add(Box.createHorizontalGlue());
        box.add(title);
        box.add(Box.createVerticalStrut(6));
        box.add(filter);
        box.add(Box.createVerticalStrut(6));
        box.add(buttons);
        return box;
    }

    private void addCurrentListener() {
        // The frame installs the real supplier via addCurrent. This button uses the hook below.
        if (currentPathSupplier != null) {
            String path = currentPathSupplier.get();
            addCurrent(path);
        }
    }

    private java.util.function.Supplier<String> currentPathSupplier;
    private Consumer<String> openInExplorer = path -> {
    };
    private Consumer<String> indexFolder = path -> {
    };

    public void setCurrentPathSupplier(java.util.function.Supplier<String> supplier) {
        this.currentPathSupplier = supplier;
    }

    public void setOpenInExplorer(Consumer<String> openInExplorer) {
        this.openInExplorer = openInExplorer == null ? path -> {
        } : openInExplorer;
    }

    public void setIndexFolder(Consumer<String> indexFolder) {
        this.indexFolder = indexFolder == null ? path -> {
        } : indexFolder;
    }

    private void createGroup() {
        String name = JOptionPane.showInputDialog(this, Messages.FAV_ADD_GROUP, Messages.FAV_NEW_GROUP);
        if (name == null) {
            return;
        }
        if (name.isBlank()) {
            onError.accept(Messages.ERR_FAV_NAME);
            return;
        }
        FavoriteNode group = tree.addGroup(selectedGroupId(), name);
        if (group == null) {
            group = tree.addGroup(null, name);
        }
        persist();
        reload();
        if (group != null) {
            select(group.id());
        }
    }

    private void openSelected() {
        FavoriteNode node = selectedNode();
        if (node != null && !node.isGroup()) {
            openPath.accept(node.path());
        }
    }

    private void renameSelected() {
        FavoriteNode node = selectedNode();
        if (node == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, Messages.FAV_RENAME, node.name());
        if (name == null) {
            return;
        }
        if (!tree.rename(node.id(), name)) {
            onError.accept(Messages.ERR_FAV_NAME);
            return;
        }
        persist();
        reload();
        select(node.id());
    }

    private void deleteSelected() {
        FavoriteNode node = selectedNode();
        if (node == null) {
            return;
        }
        if (node.isGroup() && !node.children().isEmpty()) {
            int answer = JOptionPane.showConfirmDialog(
                    this, Messages.FAV_DELETE_GROUP, Messages.FAVORITES, JOptionPane.OK_CANCEL_OPTION);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        tree.remove(node.id());
        persist();
        reload();
    }

    private void moveSelected(int direction) {
        FavoriteNode node = selectedNode();
        if (node == null) {
            return;
        }
        int index = tree.indexInParent(node.id());
        if (index < 0) {
            return;
        }
        if (!tree.move(node.id(), tree.parentId(node.id()), index + direction)) {
            onError.accept(Messages.ERR_FAV_MOVE);
            return;
        }
        persist();
        reload();
        select(node.id());
    }

    private void moveSelectedTo(String parentId) {
        FavoriteNode node = selectedNode();
        if (node == null) {
            return;
        }
        int index = parentId == null ? tree.roots().size() : Integer.MAX_VALUE;
        if (!tree.move(node.id(), parentId, index)) {
            onError.accept(Messages.ERR_FAV_MOVE);
            return;
        }
        persist();
        reload();
        select(node.id());
    }

    private void showPopup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        TreePath path = pathAt(event.getX(), event.getY());
        if (path != null) {
            view.setSelectionPath(path);
        }
        FavoriteNode node = path == null ? null : nodeOf(path);
        JPopupMenu menu = new JPopupMenu();
        if (node != null && !node.isGroup()) {
            JMenuItem open = new JMenuItem(Messages.FAV_OPEN);
            String target = node.path();
            open.addActionListener(e -> openPath.accept(target));
            menu.add(open);
            JMenuItem explorer = new JMenuItem(Messages.OPEN_IN_EXPLORER);
            explorer.setEnabled(com.fastexplorer.filecache.WindowsExplorer.available());
            explorer.setToolTipText(Messages.TIP_EXPLORER_WINDOWS);
            explorer.addActionListener(e -> openInExplorer.accept(target));
            menu.add(explorer);
            JMenuItem index = new JMenuItem(Messages.INDEX_BUILD);
            index.addActionListener(e -> indexFolder.accept(target));
            menu.add(index);
        }
        JMenuItem rename = new JMenuItem(Messages.FAV_RENAME);
        rename.setEnabled(node != null);
        rename.addActionListener(e -> renameSelected());
        JMenuItem delete = new JMenuItem(Messages.FAV_DELETE);
        delete.setEnabled(node != null);
        delete.addActionListener(e -> deleteSelected());
        JMenuItem up = new JMenuItem(Messages.FAV_UP);
        up.setEnabled(node != null);
        up.addActionListener(e -> moveSelected(-1));
        JMenuItem down = new JMenuItem(Messages.FAV_DOWN);
        down.setEnabled(node != null);
        down.addActionListener(e -> moveSelected(1));
        JMenu move = new JMenu(Messages.FAV_MOVE_TO);
        move.setEnabled(node != null);
        if (node != null) {
            JMenuItem root = new JMenuItem(Messages.FAV_MOVE_ROOT);
            root.setEnabled(tree.parentId(node.id()) != null);
            root.addActionListener(e -> moveSelectedTo(null));
            move.add(root);
            for (FavoriteTree.GroupRef group : tree.groups()) {
                if (group.id().equals(node.id())) {
                    continue;
                }
                JMenuItem item = new JMenuItem(group.breadcrumb());
                item.addActionListener(e -> moveSelectedTo(group.id()));
                move.add(item);
            }
        }
        JMenuItem newGroup = new JMenuItem(Messages.FAV_ADD_GROUP);
        newGroup.addActionListener(e -> createGroup());
        menu.add(rename);
        menu.add(delete);
        menu.addSeparator();
        menu.add(up);
        menu.add(down);
        menu.add(move);
        menu.addSeparator();
        menu.add(newGroup);
        menu.show(view, event.getX(), event.getY());
    }

    private void reload() {
        String selected = selectedId();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        FavoriteTree shown = tree.filtered(filter.getText());
        for (FavoriteNode node : shown.roots()) {
            root.add(swingNode(node));
        }
        view.setModel(new DefaultTreeModel(root));
        expandAll(new TreePath(root));
        if (selected != null) {
            select(selected);
        }
    }

    private static DefaultMutableTreeNode swingNode(FavoriteNode node) {
        DefaultMutableTreeNode swing = new DefaultMutableTreeNode(node);
        for (FavoriteNode child : node.children()) {
            swing.add(swingNode(child));
        }
        return swing;
    }

    private void expandAll(TreePath path) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        view.expandPath(path);
        for (int i = 0; i < node.getChildCount(); i++) {
            expandAll(path.pathByAddingChild(node.getChildAt(i)));
        }
    }

    private void collapseAll() {
        for (int row = view.getRowCount() - 1; row >= 0; row--) {
            view.collapseRow(row);
        }
    }

    private void select(String id) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) view.getModel().getRoot();
        TreePath path = findPath(new TreePath(root), id);
        if (path != null) {
            view.setSelectionPath(path);
            view.scrollPathToVisible(path);
        }
    }

    private TreePath findPath(TreePath path, String id) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof FavoriteNode favorite && favorite.id().equals(id)) {
            return path;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath found = findPath(path.pathByAddingChild(node.getChildAt(i)), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String selectedGroupId() {
        FavoriteNode node = selectedNode();
        if (node == null) {
            return null;
        }
        if (node.isGroup()) {
            return node.id();
        }
        return tree.parentId(node.id());
    }

    private FavoriteNode selectedNode() {
        TreePath path = view.getSelectionPath();
        return path == null ? null : nodeOf(path);
    }

    private String selectedId() {
        FavoriteNode node = selectedNode();
        return node == null ? null : node.id();
    }

    private FavoriteNode nodeAt(int x, int y) {
        TreePath path = pathAt(x, y);
        return path == null ? null : nodeOf(path);
    }

    /** Hit-test the full row, not just the label width. */
    private TreePath pathAt(int x, int y) {
        TreePath path = view.getClosestPathForLocation(x, y);
        if (path == null) {
            return null;
        }
        java.awt.Rectangle bounds = view.getPathBounds(path);
        if (bounds == null || y < bounds.y || y >= bounds.y + bounds.height) {
            return null;
        }
        return path;
    }

    private static FavoriteNode nodeOf(TreePath path) {
        if (!(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) {
            return null;
        }
        return node.getUserObject() instanceof FavoriteNode favorite ? favorite : null;
    }

    private void persist() {
        try {
            FavoritesStore.save(file, tree);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "favorites save failed", e);
            onError.accept(Messages.ERR_FAV_SAVE);
        }
    }

    private static javax.swing.AbstractAction action(Runnable runnable) {
        return new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                runnable.run();
            }
        };
    }

    private final class FavoriteTransfer extends TransferHandler {
        @Override
        public int getSourceActions(JComponent component) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent component) {
            FavoriteNode node = selectedNode();
            if (node == null) {
                return null;
            }
            String id = node.id();
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{FAVORITE_FLAVOR};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return FAVORITE_FLAVOR.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor flavor) {
                    return id;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDrop()
                    && (support.isDataFlavorSupported(FAVORITE_FLAVOR)
                    || support.isDataFlavorSupported(DataFlavor.stringFlavor)
                    || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            Drop drop = dropTarget((JTree.DropLocation) support.getDropLocation());
            try {
                if (support.isDataFlavorSupported(FAVORITE_FLAVOR)) {
                    String id = (String) support.getTransferable().getTransferData(FAVORITE_FLAVOR);
                    if (!tree.move(id, drop.parentId(), drop.index())) {
                        return false;
                    }
                } else if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    boolean any = false;
                    for (File file : files) {
                        if (file.isDirectory()) {
                            tree.addFolder(drop.parentId(), file.getName(), file.getAbsolutePath());
                            any = true;
                        }
                    }
                    if (!any) {
                        return false;
                    }
                } else {
                    String path = String.valueOf(support.getTransferable().getTransferData(DataFlavor.stringFlavor)).trim();
                    if (path.isEmpty()) {
                        return false;
                    }
                    tree.addFolder(drop.parentId(), FavoriteNames.displayName(path), path);
                }
            } catch (UnsupportedFlavorException | IOException e) {
                return false;
            }
            persist();
            reload();
            return true;
        }

        private Drop dropTarget(JTree.DropLocation location) {
            boolean filtering = !filter.getText().isBlank();
            TreePath path = location.getPath();
            int childIndex = location.getChildIndex();
            if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode swing)) {
                return new Drop(null, tree.roots().size());
            }
            if (!(swing.getUserObject() instanceof FavoriteNode node)) {
                int index = childIndex < 0 || filtering ? tree.roots().size() : childIndex;
                return new Drop(null, index);
            }
            if (node.isGroup() && (childIndex >= 0 || filtering)) {
                int index = filtering || childIndex < 0 ? node.children().size() : childIndex;
                if (filtering) {
                    FavoriteNode real = tree.find(node.id());
                    index = real == null ? 0 : real.children().size();
                }
                return new Drop(node.id(), index);
            }
            if (node.isGroup()) {
                FavoriteNode real = tree.find(node.id());
                int index = real == null ? 0 : real.children().size();
                return new Drop(node.id(), index);
            }
            String parentId = tree.parentId(node.id());
            int index = tree.indexInParent(node.id()) + 1;
            return new Drop(parentId, index);
        }
    }

    private record Drop(String parentId, int index) {
    }

    private static final class FavoriteRenderer extends DefaultTreeCellRenderer {
        @Override
        public java.awt.Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof FavoriteNode favorite) {
                setText(favorite.name());
                setIcon(favorite.isGroup() ? Icons.groupIcon() : Icons.folderIcon());
                setToolTipText(favorite.isGroup() ? null : favorite.path());
            }
            return this;
        }
    }
}
