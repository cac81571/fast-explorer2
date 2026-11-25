package com.fastexplorer.ui;

import com.fastexplorer.Messages;
import com.fastexplorer.cache.ListingCache;
import com.fastexplorer.config.AppConfig;
import com.fastexplorer.favorites.FavoriteTree;
import com.fastexplorer.favorites.FavoritesStore;
import com.fastexplorer.filecache.FileContentCache;
import com.fastexplorer.filecache.WindowsExplorer;
import com.fastexplorer.history.InputHistory;
import com.fastexplorer.history.StartupFolder;
import com.fastexplorer.index.FolderIndexer;
import com.fastexplorer.index.IndexCatalog;
import com.fastexplorer.index.IndexRecord;
import com.fastexplorer.fs.NioDirectoryLister;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.LoadStatus;
import com.fastexplorer.model.SearchHit;
import com.fastexplorer.nav.ExplorerController;
import com.fastexplorer.search.ExtensionFilter;
import com.fastexplorer.search.NameFilter;
import com.fastexplorer.util.ListedPaths;
import com.fastexplorer.util.PathStrings;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class MainFrame extends JFrame implements ExplorerController.Listener {
    private final AppConfig config;
    private final ListingCache cache;
    private final ExplorerController controller;
    private final Path screenshotFile;
    private final Runnable afterCapture;

    private final HistoryField address = new HistoryField();
    private final HistoryField includeFile = new HistoryField();
    private final HistoryField excludeFile = new HistoryField();
    private final HistoryField includeDir = new HistoryField();
    private final HistoryField excludeDir = new HistoryField();
    private final HistoryField extensions = new HistoryField();
    private final JComboBox<String> extensionPreset = new JComboBox<>();
    private final JComboBox<String> pathDisplay = new JComboBox<>(new String[]{Messages.PATH_RELATIVE, Messages.PATH_ABSOLUTE});
    private final JCheckBox includeFolders = new JCheckBox(Messages.EXT_INCLUDE_FOLDERS, true);
    private final JButton back = new JButton(Messages.BACK);
    private final JButton forward = new JButton(Messages.FORWARD);
    private final JButton up = new JButton(Messages.UP);
    private final JButton theme = new JButton();
    private final JButton cancelSearch = new JButton(Messages.CANCEL);
    private final JLabel subtitle = new JLabel(Messages.SUBTITLE);
    private final JLabel error = new JLabel(" ");
    private final JLabel status = new JLabel(Messages.LOADING);
    private final FileTableModel model = new FileTableModel();
    private final FileTableModel searchModel = new FileTableModel();
    private final JTable table = createFileTable(model);
    private final JTable searchTable = createFileTable(searchModel);
    private final JMenuItem darkItem = new JMenuItem(Messages.MENU_TOGGLE_THEME);
    private final FavoritesPanel favorites;
    private final JSplitPane split;
    private final javax.swing.Timer dividerSaver;

    private final List<SearchHit> searchHits = new ArrayList<>();
    private List<FileRow> browseRows = List.of();
    private LoadStatus browseStatus = new LoadStatus(0, 0, LoadStatus.Origin.FILESYSTEM, "");
    private boolean searchMode;
    private long searchElapsed;
    private boolean searchFinished;
    private boolean dark;
    private int searchScanned;
    private int searchErrors;
    private boolean captureScheduled;
    private boolean screenshotSearchStarted;
    private boolean suppressExtensionEvents;
    private boolean openingInitial;
    private final InputHistory inputHistory;
    private final FileContentCache fileCache;
    private final IndexCatalog indexCatalog;
    private final FolderIndexer indexer;
    private final IndexPanel indexPanel;
    private final JTabbedPane tabs = new JTabbedPane();
    private final ExecutorService fileIo = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fast-explorer-filecache");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean copyCancel = new AtomicBoolean();
    private volatile boolean copyRunning;

    public MainFrame(AppConfig config, ListingCache cache, Path screenshotFile, Runnable afterCapture) {
        super(Messages.APP_NAME);
        this.config = config;
        this.cache = cache;
        this.screenshotFile = screenshotFile;
        this.afterCapture = afterCapture;
        this.dark = config.darkTheme();
        this.controller = new ExplorerController(
                new NioDirectoryLister(),
                cache,
                config.settings(),
                SwingUtilities::invokeLater,
                this);
        this.inputHistory = openHistory();
        this.fileCache = openFileCache();
        this.indexCatalog = openCatalog();
        this.indexer = new FolderIndexer(new NioDirectoryLister(), cache, indexCatalog);
        this.indexer.setListener(record -> SwingUtilities.invokeLater(() -> onIndexUpdate(record)));
        this.indexPanel = new IndexPanel(this::openIndexedFolder, this::reindexFolder, this::cancelIndex, this::deleteIndex);
        this.openingInitial = screenshotFile == null;
        this.favorites = createFavorites();
        this.split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, favorites, new JScrollPane(table));
        this.dividerSaver = new javax.swing.Timer(400, event -> saveDivider());
        this.dividerSaver.setRepeats(false);
        build();
    }

    private FavoritesPanel createFavorites() {
        FavoriteTree loaded;
        String loadError = null;
        try {
            loaded = FavoritesStore.load(config.favoritesFile());
        } catch (IOException e) {
            loaded = new FavoriteTree();
            loadError = Messages.ERR_FAV_LOAD;
        }
        FavoritesPanel panel = new FavoritesPanel(loaded, config.favoritesFile(), this::openFavorite, this::showBanner);
        panel.setCurrentPathSupplier(() -> controller.currentPath() == null ? null : controller.currentPath().toString());
        panel.setOpenInExplorer(this::openFolderInExplorer);
        panel.setIndexFolder(this::startIndex);
        if (loadError != null) {
            String message = loadError;
            SwingUtilities.invokeLater(() -> showBanner(message));
        }
        return panel;
    }

    private void openFavorite(String pathText) {
        try {
            controller.open(PathStrings.parse(pathText, null), true, false);
        } catch (RuntimeException e) {
            showBanner(Messages.ERR_BAD_PATH);
        }
    }

    public ExplorerController controller() {
        return controller;
    }

    private void build() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(760, 480));
        if (screenshotFile != null) {
            setBounds(16, 16, 1240, 760);
        } else {
            setSize(1100, 700);
            setLocationRelativeTo(null);
        }
        setIconImages(Icons.appIcons());
        setJMenuBar(menu());

        split.setContinuousLayout(true);
        split.setResizeWeight(0);
        split.setDividerLocation(config.favoritesDivider());
        split.setName("favoritesSplit");
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (split.getDividerLocation() >= 120) {
                dividerSaver.restart();
            }
        });

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.add(toolbar(), BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(statusBar(), BorderLayout.SOUTH);
        setContentPane(root);
        configureTable(table, model, "fileTable", false);
        configureTable(searchTable, searchModel, "searchTable", true);
        tabs.addTab(Messages.TAB_LIST, new JScrollPane(table));
        tabs.addTab(Messages.TAB_SEARCH, new JScrollPane(searchTable));
        tabs.addTab(Messages.TAB_INDEX, indexPanel);
        tabs.setName("mainTabs");
        split.setRightComponent(tabs);
        installKeys();
        tuneFields();
        applyBannerColors();
        updateThemeButton();
        cancelSearch.setEnabled(false);
        if (screenshotFile != null) {
            javax.swing.Timer giveUp = new javax.swing.Timer(20_000, e -> {
                if (!captureScheduled) {
                    System.err.println("screenshot: listing did not finish");
                    System.exit(1);
                }
            });
            giveUp.setRepeats(false);
            giveUp.start();
        }

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dividerSaver.stop();
                saveDivider();
                copyCancel.set(true);
                indexer.shutdown();
                fileIo.shutdownNow();
                fileCache.close();
                indexCatalog.close();
                inputHistory.close();
                controller.shutdown();
                cache.close();
            }
        });
    }

    public void start(Path path) {
        SwingUtilities.invokeLater(() -> split.setDividerLocation(config.favoritesDivider()));
        controller.open(path, true, false);
    }

    private JMenuBar menu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu(Messages.MENU_FILE);
        JMenuItem openConfig = new JMenuItem(Messages.MENU_OPEN_CONFIG);
        openConfig.addActionListener(e -> openConfigDir());
        JMenuItem exportFavorites = new JMenuItem(Messages.MENU_FAV_EXPORT);
        exportFavorites.addActionListener(e -> favorites.exportFavorites());
        JMenuItem importFavorites = new JMenuItem(Messages.MENU_FAV_IMPORT);
        importFavorites.addActionListener(e -> favorites.importFavorites());
        JMenuItem exit = new JMenuItem(Messages.MENU_EXIT);
        exit.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        file.add(exportFavorites);
        file.add(importFavorites);
        file.addSeparator();
        JMenuItem openFileCache = new JMenuItem(Messages.MENU_OPEN_FILE_CACHE);
        openFileCache.addActionListener(e -> openFileCacheFolder());
        JMenuItem clearFileCache = new JMenuItem(Messages.MENU_CLEAR_FILE_CACHE);
        clearFileCache.addActionListener(e -> clearFileCache());
        JMenuItem clearHistory = new JMenuItem(Messages.MENU_CLEAR_HISTORY);
        clearHistory.addActionListener(e -> clearInputHistory());
        file.add(openFileCache);
        file.add(clearFileCache);
        file.add(clearHistory);
        file.addSeparator();
        file.add(openConfig);
        file.addSeparator();
        file.add(exit);

        JMenu view = new JMenu(Messages.MENU_VIEW);
        JMenuItem refresh = new JMenuItem(Messages.REFRESH);
        refresh.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        refresh.addActionListener(e -> controller.refresh());
        JMenuItem indexCurrent = new JMenuItem(Messages.MENU_INDEX_CURRENT);
        indexCurrent.addActionListener(e -> {
            if (controller.currentPath() != null) {
                startIndex(controller.currentPath().toString());
            }
        });
        darkItem.addActionListener(e -> toggleTheme());
        view.add(refresh);
        view.add(indexCurrent);
        view.add(darkItem);

        JMenu help = new JMenu(Messages.MENU_HELP);
        JMenuItem about = new JMenuItem(Messages.MENU_ABOUT);
        about.addActionListener(e -> JOptionPane.showMessageDialog(
                this,
                Messages.APP_NAME + " 1.0.0\n" + Messages.SUBTITLE + "\nキャッシュ: " + cache.description(),
                Messages.APP_NAME,
                JOptionPane.INFORMATION_MESSAGE));
        help.add(about);

        bar.add(file);
        bar.add(view);
        bar.add(help);
        return bar;
    }

    private JComponent toolbar() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));

        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel title = new JLabel(Messages.APP_NAME);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setName("appTitle");
        subtitle.setName("appSubtitle");
        titleRow.add(title);
        titleRow.add(subtitle);
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel nav = row();
        configureButton(back, Messages.TIP_BACK, e -> controller.back());
        configureButton(forward, Messages.TIP_FORWARD, e -> controller.forward());
        configureButton(up, Messages.TIP_UP, e -> controller.up());
        useArrow(back, ArrowIcon.Dir.LEFT, Messages.BACK);
        useArrow(forward, ArrowIcon.Dir.RIGHT, Messages.FORWARD);
        useArrow(up, ArrowIcon.Dir.UP, Messages.UP);
        address.setGrowHorizontally(true);
        address.setPlaceholder(Messages.ADDRESS_PLACEHOLDER);
        address.setToolTipText(Messages.TIP_ADDRESS);
        address.setName("address");
        address.setHistory(inputHistory.list(InputHistory.Bucket.PATH));
        address.setOnDelete(value -> deleteHistory(InputHistory.Bucket.PATH, value, address));
        address.addActionListener(e -> {
            if (!address.isAdjusting() && !address.isPopupVisible()) {
                goAddress();
            }
        });
        JButton go = new JButton(Messages.GO);
        configureButton(go, Messages.TIP_ADDRESS, e -> goAddress());
        JButton refresh = new JButton(Messages.REFRESH);
        configureButton(refresh, Messages.TIP_REFRESH, e -> controller.refresh());
        configureButton(theme, Messages.TIP_THEME, e -> toggleTheme());
        nav.add(back);
        nav.add(Box.createHorizontalStrut(4));
        nav.add(forward);
        nav.add(Box.createHorizontalStrut(4));
        nav.add(up);
        nav.add(Box.createHorizontalStrut(8));
        nav.add(address);
        nav.add(Box.createHorizontalStrut(6));
        nav.add(go);
        nav.add(Box.createHorizontalStrut(4));
        nav.add(refresh);
        nav.add(Box.createHorizontalStrut(4));
        nav.add(theme);

        wirePattern(includeFile, InputHistory.Bucket.INCLUDE_FILE, Messages.INCLUDE_FILE_PLACEHOLDER, Messages.TIP_INCLUDE_FILE, "includeFile");
        wirePattern(excludeFile, InputHistory.Bucket.EXCLUDE_FILE, Messages.EXCLUDE_FILE_PLACEHOLDER, Messages.TIP_EXCLUDE_FILE, "excludeFile");
        wirePattern(includeDir, InputHistory.Bucket.INCLUDE_DIR, Messages.INCLUDE_DIR_PLACEHOLDER, Messages.TIP_INCLUDE_DIR, "includeDir");
        wirePattern(excludeDir, InputHistory.Bucket.EXCLUDE_DIR, Messages.EXCLUDE_DIR_PLACEHOLDER, Messages.TIP_EXCLUDE_DIR, "excludeDir");
        JButton searchButton = new JButton(Messages.SEARCH);
        configureButton(searchButton, Messages.TIP_SEARCH, e -> startSearch(true));
        configureButton(cancelSearch, Messages.TIP_CANCEL, e -> cancelCurrent());
        pathDisplay.setName("pathDisplay");
        pathDisplay.setToolTipText(Messages.TIP_PATH_DISPLAY);
        pathDisplay.setSelectedIndex(config.relativePaths() ? 0 : 1);
        pathDisplay.addActionListener(e -> {
            config.setRelativePaths(pathDisplay.getSelectedIndex() == 0);
            try {
                config.save();
            } catch (IOException ignored) {
                // The choice still applies for this session.
            }
            if (searchMode) {
                renderSearch(searchElapsed, searchFinished);
            }
        });
        JPanel patternGrid = new JPanel(new GridBagLayout());
        patternGrid.setOpaque(false);
        patternGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        addPattern(patternGrid, 0, 0, Messages.INCLUDE_FILE, includeFile);
        addPattern(patternGrid, 0, 2, Messages.EXCLUDE_FILE, excludeFile);
        GridBagConstraints buttons = patternConstraints(4, 0, 0);
        buttons.fill = GridBagConstraints.NONE;
        buttons.insets = new Insets(0, 4, 4, 0);
        patternGrid.add(searchButton, buttons);
        buttons.gridx = 5;
        patternGrid.add(cancelSearch, buttons);
        addPattern(patternGrid, 1, 0, Messages.INCLUDE_DIR, includeDir);
        addPattern(patternGrid, 1, 2, Messages.EXCLUDE_DIR, excludeDir);
        GridBagConstraints pathStyle = patternConstraints(4, 1, 0);
        pathStyle.gridwidth = 2;
        pathStyle.fill = GridBagConstraints.NONE;
        pathStyle.anchor = GridBagConstraints.WEST;
        patternGrid.add(pathDisplay, pathStyle);
        JLabel extensionLabel = new JLabel(Messages.EXT_LABEL);
        extensions.setPlaceholder(Messages.EXT_PLACEHOLDER);
        extensions.setToolTipText(Messages.TIP_EXT);
        extensions.setName("extensionFilter");
        extensions.setHistory(inputHistory.list(InputHistory.Bucket.EXTENSION));
        extensions.setOnDelete(value -> deleteHistory(InputHistory.Bucket.EXTENSION, value, extensions));
        includeFolders.setToolTipText(Messages.TIP_EXT_FOLDERS);
        includeFolders.setOpaque(false);
        fillExtensionPresets();
        extensionPreset.setName("extensionPreset");
        extensionPreset.addActionListener(e -> applyExtensionPreset());

        JPanel extensionRow = row();
        extensionRow.add(extensionLabel);
        extensionRow.add(Box.createHorizontalStrut(6));
        extensionRow.add(extensions);
        extensionRow.add(Box.createHorizontalStrut(6));
        extensionRow.add(extensionPreset);
        extensionRow.add(Box.createHorizontalStrut(8));
        extensionRow.add(includeFolders);
        extensionRow.add(Box.createHorizontalGlue());

        error.setOpaque(true);
        error.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        error.setVisible(false);
        error.setAlignmentX(Component.LEFT_ALIGNMENT);

        box.add(titleRow);
        box.add(Box.createVerticalStrut(8));
        box.add(nav);
        box.add(Box.createVerticalStrut(6));
        box.add(patternGrid);
        box.add(Box.createVerticalStrut(6));
        box.add(extensionRow);
        box.add(Box.createVerticalStrut(6));
        box.add(error);
        return box;
    }

    private JPanel statusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        status.setName("statusBar");
        bar.add(status, BorderLayout.CENTER);
        return bar;
    }

    private JTable createFileTable(FileTableModel tableModel) {
        return new JTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int view = rowAtPoint(event.getPoint());
                if (view < 0) {
                    return null;
                }
                int modelRow = convertRowIndexToModel(view);
                if (modelRow < 0) {
                    return null;
                }
                return ListedPaths.fullDisplay(tableModel.rowAt(modelRow).path());
            }
        };
    }

    private void configureTable(JTable target, FileTableModel tableModel, String name, boolean showLocation) {
        target.setFillsViewportHeight(true);
        target.setShowVerticalLines(false);
        target.setShowHorizontalLines(true);
        target.setIntercellSpacing(new Dimension(0, 1));
        target.setRowHeight(28);
        target.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        target.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        target.setName(name);

        TableRowSorter<FileTableModel> sorter = new TableRowSorter<>(tableModel);
        sorter.setComparator(0, FileRow.BY_NAME);
        sorter.setComparator(1, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(3, (Long left, Long right) -> Long.compare(left, right));
        sorter.setComparator(4, (Long left, Long right) -> Long.compare(left, right));
        sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(0, javax.swing.SortOrder.ASCENDING)));
        target.setRowSorter(sorter);

        target.setDefaultRenderer(FileRow.class, new NameRenderer());
        target.setDefaultRenderer(Long.class, new NumberRenderer());

        target.getColumnModel().getColumn(0).setPreferredWidth(420);
        target.getColumnModel().getColumn(1).setPreferredWidth(showLocation ? 280 : 0);
        target.getColumnModel().getColumn(2).setPreferredWidth(90);
        target.getColumnModel().getColumn(3).setPreferredWidth(110);
        target.getColumnModel().getColumn(4).setPreferredWidth(160);
        setLocationColumnVisible(target, showLocation);

        target.setDragEnabled(true);
        target.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent component) {
                return COPY;
            }

            @Override
            protected java.awt.datatransfer.Transferable createTransferable(JComponent component) {
                FileRow row = selectedFileRow(target);
                if (row == null || row.kind() != EntryKind.DIRECTORY) {
                    return null;
                }
                return new StringSelection(row.path().toString());
            }
        });
        target.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelected(target);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                showFileMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showFileMenu(e);
            }

            private void showFileMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = target.rowAtPoint(e.getPoint());
                if (row >= 0 && !target.isRowSelected(row)) {
                    target.setRowSelectionInterval(row, row);
                }
                showContextMenu(target, e.getX(), e.getY(), selectedFileRow(target));
            }
        });
        target.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-row");
        target.getActionMap().put("open-row", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSelected(target);
            }
        });
    }

    private void installKeys() {
        var root = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var actions = getRootPane().getActionMap();
        bind(root, actions, "back", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK), () -> controller.back());
        bind(root, actions, "forward", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK), () -> controller.forward());
        bind(root, actions, "up", KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK), () -> controller.up());
        bind(root, actions, "refresh", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), () -> controller.refresh());
        bind(root, actions, "address", KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK), () -> address.focusEditor(true));
        bind(root, actions, "find", KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), () -> includeFile.focusEditor(true));
        for (JTable fileTable : List.of(table, searchTable)) {
            fileTable.getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "up-folder");
            fileTable.getActionMap().put("up-folder", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    controller.up();
                }
            });
        }
    }

    private static void bind(
            javax.swing.InputMap inputs,
            javax.swing.ActionMap actions,
            String name,
            KeyStroke stroke,
            Runnable runnable
    ) {
        inputs.put(stroke, name);
        actions.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runnable.run();
            }
        });
    }

    private void goAddress() {
        String text = address.getText();
        try {
            Path parsed = PathStrings.parse(text, controller.currentPath());
            controller.open(parsed, true, false);
            table.requestFocusInWindow();
        } catch (IllegalArgumentException e) {
            showBanner(text.isBlank() ? Messages.ERR_EMPTY_PATH : Messages.ERR_BAD_PATH);
        }
    }

    private void openSelected(JTable source) {
        FileRow row = selectedFileRow(source);
        if (row == null) {
            return;
        }
        controller.activate(row.path(), row.kind());
    }

    private JTable activeFileTable() {
        return tabs.getSelectedIndex() == 1 ? searchTable : table;
    }

    private FileTableModel modelOf(JTable source) {
        return source == searchTable ? searchModel : model;
    }

    private FileRow selectedFileRow(JTable source) {
        int view = source.getSelectedRow();
        if (view < 0) {
            return null;
        }
        return modelOf(source).rowAt(source.convertRowIndexToModel(view));
    }

    private void addSelectedFavorite() {
        FileRow row = selectedFileRow(activeFileTable());
        if (row == null || row.kind() != EntryKind.DIRECTORY) {
            return;
        }
        favorites.addFromList(row.path().toString());
    }

    private void saveDivider() {
        int location = split.getDividerLocation();
        if (location < 120) {
            return;
        }
        config.setFavoritesDivider(location);
        try {
            config.save();
        } catch (IOException ignored) {
            // The next successful save keeps the position.
        }
    }

    private void toggleTheme() {
        dark = !dark;
        config.setDarkTheme(dark);
        saveDivider();
        Theme.install(dark);
        int location = split.getDividerLocation();
        SwingUtilities.updateComponentTreeUI(this);
        favorites.restyle();
        split.setDividerLocation(location);
        tuneFields();
        applyBannerColors();
        updateThemeButton();
        subtitle.setFont(Theme.baseFont());
        table.setRowHeight(28);
        searchTable.setRowHeight(28);
    }

    private void updateThemeButton() {
        theme.setText(dark ? Messages.THEME_LIGHT : Messages.THEME_DARK);
        darkItem.setText(dark ? Messages.THEME_LIGHT : Messages.MENU_TOGGLE_THEME);
    }

    private void tuneFields() {
        int fieldHeight = Math.max(address.getPreferredSize().height, includeFile.getPreferredSize().height);
        address.setMinimumSize(new Dimension(160, fieldHeight));
        address.setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldHeight));
        for (HistoryField field : List.of(includeFile, excludeFile, includeDir, excludeDir)) {
            field.setMinimumSize(new Dimension(120, fieldHeight));
            field.setPreferredSize(new Dimension(220, fieldHeight));
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldHeight));
        }
        pathDisplay.setMinimumSize(new Dimension(88, fieldHeight));
        pathDisplay.setPreferredSize(new Dimension(96, fieldHeight));
        pathDisplay.setMaximumSize(new Dimension(120, fieldHeight));
        extensions.setMinimumSize(new Dimension(160, fieldHeight));
        extensions.setPreferredSize(new Dimension(220, fieldHeight));
        extensions.setMaximumSize(new Dimension(280, fieldHeight));
        int presetHeight = Math.max(fieldHeight, extensionPreset.getPreferredSize().height);
        extensionPreset.setMinimumSize(new Dimension(96, presetHeight));
        extensionPreset.setPreferredSize(new Dimension(120, presetHeight));
        extensionPreset.setMaximumSize(new Dimension(160, presetHeight));
        Dimension folderSize = includeFolders.getPreferredSize();
        includeFolders.setMinimumSize(folderSize);
        includeFolders.setMaximumSize(folderSize);
        address.setAlignmentX(Component.LEFT_ALIGNMENT);
        includeFile.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private void applyBannerColors() {
        if (dark) {
            error.setBackground(new Color(0x3F1D1D));
            error.setForeground(new Color(0xF5C2C2));
        } else {
            error.setBackground(new Color(0xFDECEC));
            error.setForeground(new Color(0x9B1C1C));
        }
        subtitle.setForeground(UIColor());
    }

    private Color UIColor() {
        Color color = javax.swing.UIManager.getColor("Label.disabledForeground");
        return color == null ? Color.GRAY : color;
    }

    private void showBanner(String message) {
        error.setText(message);
        error.setVisible(true);
        error.revalidate();
    }

    private void clearBanner() {
        error.setVisible(false);
        error.setText(" ");
    }

    private void setLocationColumnVisible(JTable target, boolean visible) {
        TableColumn column = target.getColumn(Messages.COL_LOCATION);
        if (visible) {
            column.setMinWidth(80);
            column.setMaxWidth(4000);
            column.setPreferredWidth(280);
        } else {
            column.setMinWidth(0);
            column.setMaxWidth(0);
            column.setPreferredWidth(0);
        }
    }

    private void showListing(Path directory, List<FileEntry> entries, LoadStatus loadStatus) {
        searchMode = false;
        cancelSearch.setEnabled(false);
        clearBanner();
        String parent = directory.toString();
        List<FileRow> rows = entries.stream()
                .map(entry -> new FileRow(
                        entry.name(),
                        parent,
                        entry.kind(),
                        entry.size(),
                        entry.modifiedEpochMillis(),
                        directory.resolve(entry.name())))
                .toList();
        browseRows = rows;
        browseStatus = loadStatus;
        renderBrowse();
        selectPending();
        if (!address.isFocusOwner() && !patternFocused()) {
            table.requestFocusInWindow();
        }
        maybeCapture(loadStatus);
    }

    private void selectPending() {
        String name = controller.pendingSelection();
        if (name == null) {
            return;
        }
        for (int i = 0; i < model.getRowCount(); i++) {
            if (name.equals(model.rowAt(i).name())) {
                int view = table.convertRowIndexToView(i);
                if (view >= 0) {
                    table.getSelectionModel().setSelectionInterval(view, view);
                    table.scrollRectToVisible(table.getCellRect(view, 0, true));
                }
                return;
            }
        }
    }

    private void renderSearch(long elapsedMillis, boolean done) {
        searchElapsed = elapsedMillis;
        searchFinished = done;
        Path root = controller.currentPath();
        boolean relative = config.relativePaths();
        List<FileRow> rows = searchHits.stream()
                .map(hit -> new FileRow(
                        hit.name(),
                        locationText(root, hit.path(), relative),
                        hit.kind(),
                        hit.size(),
                        hit.modifiedEpochMillis(),
                        hit.path()))
                .sorted(FileRow.BY_NAME)
                .toList();
        int cap = config.settings().searchMaxResults();
        if (rows.size() > cap) {
            rows = rows.subList(0, cap);
        }
        searchModel.setRows(rows);
        String detail;
        if (done) {
            detail = Messages.SEARCH_DONE + " · 走査 " + searchScanned + " フォルダ";
        } else if (searchScanned == 0) {
            detail = Messages.SEARCH_FROM_CACHE;
        } else {
            detail = Messages.SEARCH_FROM_CACHE + " · 走査 " + searchScanned + " フォルダ";
        }
        if (searchErrors > 0) {
            detail += " · 失敗 " + searchErrors;
        }
        String filterText = currentFilter().describe();
        if (!filterText.isEmpty()) {
            detail += " · " + filterText;
        }
        String line = Messages.items(rows.size(), elapsedMillis, Messages.SOURCE_SEARCH, detail);
        if (done && rows.isEmpty()) {
            line += " · " + Messages.NO_HITS;
        }
        status.setText(line);
    }

    private void capSearchHits() {
        int cap = config.settings().searchMaxResults();
        if (searchHits.size() > cap) {
            searchHits.subList(cap, searchHits.size()).clear();
        }
    }

    private void maybeCapture(LoadStatus loadStatus) {
        if (screenshotFile == null || screenshotSearchStarted || captureScheduled) {
            return;
        }
        if (loadStatus.origin() == LoadStatus.Origin.REFRESHING) {
            return;
        }
        screenshotSearchStarted = true;
        javax.swing.Timer timer = new javax.swing.Timer(500, e -> beginScreenshotSearch());
        timer.setRepeats(false);
        timer.start();
    }

    private void beginScreenshotSearch() {
        captureScheduled = true;
        captureTo(screenshotFile, false);
        includeFile.setText("txt");
        startSearch(true);
    }

    private void captureSearchScreenshot() {
        if (controller.currentPath() != null) {
            indexer.start(controller.currentPath(), 0);
        }
        javax.swing.Timer indexShot = new javax.swing.Timer(1200, e -> {
            tabs.setSelectedComponent(indexPanel);
            refreshIndex();
            Path indexFile = screenshotFile.resolveSibling(
                    screenshotFile.getFileName().toString().replace(".png", "") + "-index.png");
            SwingUtilities.invokeLater(() -> captureTo(indexFile, true));
        });
        indexShot.setRepeats(false);
        indexShot.start();
    }

    private void scheduleCapture() {
        if (screenshotFile == null || captureScheduled) {
            return;
        }
        captureScheduled = true;
        javax.swing.Timer timer = new javax.swing.Timer(800, e -> capture());
        timer.setRepeats(false);
        timer.start();
    }

    private void capture() {
        captureTo(screenshotFile, true);
    }

    private void captureTo(Path file, boolean exitAfter) {
        try {
            toFront();
            repaint();
            java.awt.Toolkit toolkit = java.awt.Toolkit.getDefaultToolkit();
            toolkit.sync();
            java.awt.Dimension screen = toolkit.getScreenSize();
            java.awt.image.BufferedImage image = new java.awt.Robot().createScreenCapture(
                    new java.awt.Rectangle(0, 0, screen.width, screen.height));
            if (file.getParent() != null) {
                java.nio.file.Files.createDirectories(file.getParent());
            }
            javax.imageio.ImageIO.write(image, "png", file.toFile());
            System.out.println("screenshot: " + file.toAbsolutePath());
            if (exitAfter && afterCapture != null) {
                afterCapture.run();
            }
        } catch (Exception e) {
            System.err.println("screenshot failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void openConfigDir() {
        Thread thread = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(config.dataDir().toFile());
                } else {
                    SwingUtilities.invokeLater(() -> showBanner(Messages.ERR_DESKTOP));
                }
            } catch (Exception e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                SwingUtilities.invokeLater(() -> showBanner(Messages.ERR_OPEN_FILE + ": " + detail));
            }
        }, "open-config");
        thread.setDaemon(true);
        thread.start();
    }

    private void wirePattern(HistoryField field, InputHistory.Bucket bucket, String placeholder, String tip, String name) {
        field.setGrowHorizontally(true);
        field.setPlaceholder(placeholder);
        field.setToolTipText(tip);
        field.setName(name);
        field.setHistory(inputHistory.list(bucket));
        field.setOnDelete(value -> deleteHistory(bucket, value, field));
    }

    private void rememberPattern(HistoryField field, InputHistory.Bucket bucket) {
        inputHistory.remember(bucket, field.getText());
        field.setHistory(inputHistory.list(bucket));
    }

    private static void addPattern(JPanel grid, int row, int labelColumn, String label, HistoryField field) {
        JLabel caption = new JLabel(label);
        if (field.getEditor().getEditorComponent() instanceof java.awt.Component editor) {
            caption.setLabelFor(editor);
        }
        grid.add(caption, patternConstraints(labelColumn, row, 0, GridBagConstraints.NONE));
        grid.add(field, patternConstraints(labelColumn + 1, row, 1, GridBagConstraints.HORIZONTAL));
    }

    private static GridBagConstraints patternConstraints(int x, int y, double weightX, int fill) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.weightx = weightX;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, 4, 6);
        constraints.fill = fill;
        return constraints;
    }

    private static GridBagConstraints patternConstraints(int x, int y, double weightX) {
        return patternConstraints(x, y, weightX, GridBagConstraints.NONE);
    }

    private boolean patternFocused() {
        return includeFile.isFocusOwner()
                || excludeFile.isFocusOwner()
                || includeDir.isFocusOwner()
                || excludeDir.isFocusOwner();
    }

    private NameFilter currentNames() {
        return NameFilter.parse(
                includeFile.getText(),
                excludeFile.getText(),
                includeDir.getText(),
                excludeDir.getText());
    }

    private static String locationText(Path root, Path path, boolean relative) {
        if (relative && root != null) {
            return ListedPaths.relativeDisplay(root, path, PathStrings.isWindows());
        }
        return ListedPaths.fullDisplay(path);
    }

    private List<FileRow> selectedRows() {
        JTable source = activeFileTable();
        FileTableModel tableModel = modelOf(source);
        List<FileRow> rows = new ArrayList<>();
        for (int view : source.getSelectedRows()) {
            int modelRow = source.convertRowIndexToModel(view);
            if (modelRow >= 0) {
                rows.add(tableModel.rowAt(modelRow));
            }
        }
        return rows;
    }

    private void copyRows(CopyKind kind, FileRow fallback) {
        List<FileRow> rows = selectedRows();
        if (rows.isEmpty() && fallback != null) {
            rows = List.of(fallback);
        }
        if (rows.isEmpty()) {
            return;
        }
        Path root = controller.currentPath();
        boolean windows = PathStrings.isWindows();
        List<String> lines = new ArrayList<>();
        for (FileRow row : rows) {
            boolean directory = row.kind() == EntryKind.DIRECTORY;
            lines.add(switch (kind) {
                case FULL -> ListedPaths.fullDisplay(row.path());
                case RELATIVE -> root == null
                        ? ListedPaths.fullDisplay(row.path())
                        : ListedPaths.relativeDisplay(root, row.path(), windows);
                case FILE_NAME -> ListedPaths.fileName(row.path());
                case FOLDER_NAME -> ListedPaths.folderName(row.path(), directory);
            });
        }
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(ListedPaths.joinLines(lines, windows)), null);
        status.setText(Messages.copied(lines.size()));
    }

    private enum CopyKind {
        FULL, RELATIVE, FILE_NAME, FOLDER_NAME
    }

    private static JPanel row() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setOpaque(false);
        return panel;
    }

    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            FileRow file = value instanceof FileRow rowValue ? rowValue : null;
            Component component = super.getTableCellRendererComponent(
                    table, file == null ? "" : file.name(), isSelected, hasFocus, row, column);
            if (component instanceof JLabel label && file != null) {
                label.setIcon(Icons.forKind(file.kind()));
                label.setIconTextGap(8);
            }
            return component;
        }
    }

    private static final class NumberRenderer extends DefaultTableCellRenderer {
        private NumberRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            int modelColumn = table.convertColumnIndexToModel(column);
            String text = "";
            if (value instanceof Long number) {
                text = modelColumn == 3 ? Formatters.size(number) : (number <= 0 ? "" : Formatters.time(number));
            }
            return super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
        }
    }

    private InputHistory openHistory() {
        try {
            return InputHistory.open(config.historyDatabaseFile(), config.historyMaxEntries());
        } catch (RuntimeException e) {
            return InputHistory.inMemory("history-fallback", config.historyMaxEntries());
        }
    }

    private FileContentCache openFileCache() {
        try {
            return FileContentCache.open(config.fileCacheDirectory(), config.fileCacheMaxBytes());
        } catch (RuntimeException e) {
            return FileContentCache.inMemory("filecache-fallback", config.fileCacheDirectory(), config.fileCacheMaxBytes(), System::currentTimeMillis);
        }
    }

    private IndexCatalog openCatalog() {
        try {
            return IndexCatalog.open(config.indexDatabaseFile());
        } catch (RuntimeException e) {
            return IndexCatalog.inMemory("index-fallback");
        }
    }

    private void useArrow(JButton button, ArrowIcon.Dir direction, String accessibleName) {
        button.setText("");
        button.setIcon(new ArrowIcon(direction));
        button.setMargin(new java.awt.Insets(2, 6, 2, 6));
        Dimension size = new Dimension(34, 28);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        button.getAccessibleContext().setAccessibleName(accessibleName);
    }

    private void showContextMenu(java.awt.Component owner, int x, int y, FileRow row) {
        contextMenu(row).show(owner, x, y);
    }

    private JPopupMenu contextMenu(FileRow row) {
        JPopupMenu menu = new JPopupMenu();
        menu.setLightWeightPopupEnabled(true);
        boolean directory = row != null && row.kind() == EntryKind.DIRECTORY;
        boolean file = row != null && !directory;
        boolean windows = WindowsExplorer.available();
        if (directory) {
            JMenuItem favorite = new JMenuItem(Messages.FAV_ADD_SELECTED);
            favorite.addActionListener(e -> addSelectedFavorite());
            menu.add(favorite);
            JMenuItem explorer = new JMenuItem(Messages.OPEN_IN_EXPLORER);
            explorer.setEnabled(windows);
            explorer.setToolTipText(windows ? null : Messages.TIP_EXPLORER_WINDOWS);
            explorer.addActionListener(e -> openFolderInExplorer(row.path().toString()));
            menu.add(explorer);
            JMenuItem index = new JMenuItem(Messages.INDEX_BUILD);
            index.addActionListener(e -> startIndex(row.path().toString()));
            menu.add(index);
        }
        if (file) {
            JMenuItem reveal = new JMenuItem(Messages.REVEAL_IN_EXPLORER);
            reveal.setEnabled(windows);
            reveal.setToolTipText(windows ? null : Messages.TIP_EXPLORER_WINDOWS);
            reveal.addActionListener(e -> revealFileInExplorer(row.path().toString()));
            menu.add(reveal);
            JMenuItem cache = new JMenuItem(Messages.OPEN_LOCAL_CACHE);
            cache.setToolTipText(Messages.TIP_LOCAL_CACHE);
            cache.addActionListener(e -> openLocalCopy(row, false));
            menu.add(cache);
            JMenuItem readOnly = new JMenuItem(Messages.OPEN_LOCAL_CACHE_RO);
            readOnly.setToolTipText(Messages.TIP_LOCAL_CACHE_RO);
            readOnly.addActionListener(e -> openLocalCopy(row, true));
            menu.add(readOnly);
        }
        if (row != null) {
            menu.addSeparator();
            JMenu copy = new JMenu(Messages.MENU_COPY);
            copy.add(copyItem(Messages.COPY_FULL, row, CopyKind.FULL));
            copy.add(copyItem(Messages.COPY_RELATIVE, row, CopyKind.RELATIVE));
            copy.add(copyItem(Messages.COPY_FILE_NAME, row, CopyKind.FILE_NAME));
            copy.add(copyItem(Messages.COPY_FOLDER_NAME, row, CopyKind.FOLDER_NAME));
            menu.add(copy);
        }
        return menu;
    }

    private JMenuItem copyItem(String label, FileRow row, CopyKind kind) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> copyRows(kind, row));
        return item;
    }

    private void cancelCurrent() {
        if (copyRunning) {
            copyCancel.set(true);
            return;
        }
        if (indexer.hasRunning()) {
            indexer.cancelAll();
            return;
        }
        controller.cancelAndReload();
    }

    private void rememberPath(Path directory) {
        if (directory == null) {
            return;
        }
        String text = directory.toString();
        inputHistory.remember(InputHistory.Bucket.PATH, text);
        address.setHistory(inputHistory.list(InputHistory.Bucket.PATH));
        config.setLastPath(text);
        try {
            config.save();
        } catch (IOException ignored) {
            // The folder is still open. The path is saved on the next success.
        }
    }

    private void deleteHistory(InputHistory.Bucket bucket, String value, HistoryField field) {
        inputHistory.delete(bucket, value);
        field.setHistory(inputHistory.list(bucket));
    }

    private void clearInputHistory() {
        inputHistory.clearAll();
        address.setHistory(List.of());
        includeFile.setHistory(List.of());
        excludeFile.setHistory(List.of());
        includeDir.setHistory(List.of());
        excludeDir.setHistory(List.of());
        extensions.setHistory(List.of());
        status.setText("入力履歴を消去しました");
    }

    private void openFolderInExplorer(String path) {
        fileIo.submit(() -> {
            try {
                WindowsExplorer.openFolder(path);
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> showBanner(e.getMessage()));
            }
        });
    }

    private void revealFileInExplorer(String path) {
        fileIo.submit(() -> {
            try {
                WindowsExplorer.selectFile(path);
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> showBanner(e.getMessage()));
            }
        });
    }

    private void openLocalCopy(FileRow row, boolean readOnly) {
        if (row == null) {
            return;
        }
        copyCancel.set(false);
        copyRunning = true;
        cancelSearch.setEnabled(true);
        String name = row.name();
        status.setText(Messages.copying(name, 0, Math.max(0, row.size())));
        fileIo.submit(() -> {
            try {
                long size = row.size();
                long modified = row.modifiedEpochMillis();
                if (size < 0) {
                    var attrs = java.nio.file.Files.readAttributes(row.path(), java.nio.file.attribute.BasicFileAttributes.class);
                    size = attrs.size();
                    modified = attrs.lastModifiedTime().toMillis();
                }
                Path local = fileCache.materialize(row.path(), row.path().toString(), size, modified, readOnly,
                        copyCancel::get,
                        (copied, total) -> SwingUtilities.invokeLater(() -> {
                            if (copyRunning) {
                                status.setText(Messages.copying(name, copied, total));
                            }
                        }));
                if (copyCancel.get()) {
                    SwingUtilities.invokeLater(() -> status.setText(Messages.COPY_CANCELLED));
                    return;
                }
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(local.toFile());
                }
                String message = readOnly ? Messages.openedReadOnly(local.toString()) : Messages.openedWritable(local.toString());
                SwingUtilities.invokeLater(() -> status.setText(message));
            } catch (CancellationException e) {
                SwingUtilities.invokeLater(() -> status.setText(Messages.COPY_CANCELLED));
            } catch (Exception e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                SwingUtilities.invokeLater(() -> showBanner(Messages.ERR_OPEN_FILE + ": " + detail));
            } finally {
                copyRunning = false;
            }
        });
    }

    private void openFileCacheFolder() {
        fileIo.submit(() -> {
            try {
                java.nio.file.Files.createDirectories(fileCache.root());
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(fileCache.root().toFile());
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> showBanner(Messages.ERR_OPEN_FILE + ": " + e.getMessage()));
            }
        });
    }

    private void clearFileCache() {
        int answer = JOptionPane.showConfirmDialog(this, Messages.CLEAR_CACHE_CONFIRM, Messages.MENU_CLEAR_FILE_CACHE,
                JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        fileIo.submit(() -> {
            try {
                fileCache.clear();
                SwingUtilities.invokeLater(() -> status.setText("ローカルキャッシュを消去しました"));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> showBanner(e.getMessage()));
            }
        });
    }

    private void startIndex(String pathText) {
        try {
            Path path = PathStrings.parse(pathText, controller.currentPath());
            if (!indexer.start(path, config.indexMaxDepth(), currentNames())) {
                status.setText(Messages.INDEX_BUSY);
                return;
            }
            tabs.setSelectedComponent(indexPanel);
            status.setText(Messages.indexing(path.toString(), 0, 0, 0));
        } catch (RuntimeException e) {
            showBanner(Messages.ERR_BAD_PATH);
        }
    }

    private void onIndexUpdate(IndexRecord record) {
        refreshIndex();
        status.setText(switch (record.status()) {
            case RUNNING -> Messages.indexing(record.displayPath(), record.folderCount(), record.fileCount(), record.durationMs());
            case DONE -> "インデックス完了 · " + record.displayPath() + " · フォルダ " + record.folderCount()
                    + " · ファイル " + record.fileCount();
            case CANCELLED -> "インデックスを中止しました · " + record.displayPath();
            case ERROR -> "インデックスに失敗しました · " + record.message();
        });
    }

    private void refreshIndex() {
        indexPanel.setRecords(indexCatalog.list());
    }

    private void openIndexedFolder(IndexRecord record) {
        try {
            controller.open(PathStrings.parse(record.displayPath(), null), true, false);
            tabs.setSelectedIndex(0);
        } catch (RuntimeException e) {
            showBanner(Messages.ERR_BAD_PATH);
        }
    }

    private void reindexFolder(IndexRecord record) {
        startIndex(record.displayPath());
    }

    private void cancelIndex(IndexRecord record) {
        indexer.cancel(record.rootKey());
    }

    private void deleteIndex(IndexRecord record) {
        int answer = JOptionPane.showConfirmDialog(this, Messages.DELETE_INDEX_CONFIRM, Messages.TAB_INDEX,
                JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        indexer.cancel(record.rootKey());
        indexCatalog.delete(record.rootKey());
        cache.deleteUnder(record.rootKey());
        refreshIndex();
    }

    private static void configureButton(JButton button, String tip, java.awt.event.ActionListener action) {
        button.setToolTipText(tip);
        button.addActionListener(action);
        button.setFocusable(false);
    }

    private void showListTab() {
        if (tabs.getTabCount() > 0 && tabs.getSelectedIndex() != 0) {
            tabs.setSelectedIndex(0);
        }
    }

    private void showSearchTab() {
        if (tabs.getTabCount() > 1) {
            tabs.setSelectedIndex(1);
        }
    }

    @Override
    public void onLoadStarted(Path directory) {
        showListTab();
        searchMode = false;
        cancelSearch.setEnabled(false);
        clearBanner();
        status.setText(Messages.LOADING);
        setTitle(Messages.APP_NAME + " — " + directory);
    }

    @Override
    public void onLocation(String displayPath, boolean canBack, boolean canForward, boolean canUp) {
        if (!Objects.equals(address.getText(), displayPath)) {
            address.setText(displayPath);
            address.setCaretPosition(address.getText().length());
        }
        back.setEnabled(canBack);
        forward.setEnabled(canForward);
        up.setEnabled(canUp);
        setTitle(Messages.APP_NAME + " — " + displayPath);
    }

    @Override
    public void onListing(Path directory, List<FileEntry> entries, LoadStatus loadStatus) {
        openingInitial = false;
        showListing(directory, entries, loadStatus);
        rememberPath(directory);
    }

    @Override
    public void onError(String message, boolean keepRows) {
        showBanner(message);
        if (!keepRows) {
            if (searchMode) {
                searchModel.setRows(List.of());
            } else {
                model.setRows(List.of());
            }
        }
        if (!searchMode) {
            status.setText(message);
        }
        if (StartupFolder.shouldFallbackHome(openingInitial, keepRows)) {
            openingInitial = false;
            Path home = Path.of(System.getProperty("user.home"));
            Path current = controller.currentPath();
            if (current != null && !PathStrings.cacheKey(current).equals(PathStrings.cacheKey(home))) {
                showBanner(Messages.START_FALLBACK + " " + message);
                controller.open(home, true, false);
            }
        }
    }

    @Override
    public void onSearchStarted(String query) {
        searchMode = true;
        searchScanned = 0;
        searchErrors = 0;
        searchHits.clear();
        searchModel.setRows(List.of());
        showSearchTab();
        cancelSearch.setEnabled(true);
        clearBanner();
        status.setText(Messages.SEARCHING);
    }

    @Override
    public void onSearchHits(List<SearchHit> cacheHits, long elapsedMillis) {
        searchHits.clear();
        searchHits.addAll(cacheHits);
        capSearchHits();
        renderSearch(elapsedMillis, false);
    }

    @Override
    public void onSearchDirectory(String dirKey, List<SearchHit> matches, long elapsedMillis) {
        searchHits.removeIf(hit -> hit.dirKey().equals(dirKey));
        searchHits.addAll(matches);
        capSearchHits();
        renderSearch(elapsedMillis, false);
    }

    @Override
    public void onSearchProgress(int scannedDirs, int errors, long elapsedMillis) {
        searchScanned = scannedDirs;
        searchErrors = errors;
        renderSearch(elapsedMillis, false);
    }

    @Override
    public void onSearchFinished(int scannedDirs, int errors, long elapsedMillis) {
        searchScanned = scannedDirs;
        searchErrors = errors;
        cancelSearch.setEnabled(false);
        renderSearch(elapsedMillis, true);
        if (screenshotFile != null) {
            javax.swing.Timer timer = new javax.swing.Timer(700, e -> captureSearchScreenshot());
            timer.setRepeats(false);
            timer.start();
            return;
        }
        scheduleCapture();
    }

    private void startSearch(boolean remember) {
        showSearchTab();
        if (remember) {
            rememberPattern(includeFile, InputHistory.Bucket.INCLUDE_FILE);
            rememberPattern(excludeFile, InputHistory.Bucket.EXCLUDE_FILE);
            rememberPattern(includeDir, InputHistory.Bucket.INCLUDE_DIR);
            rememberPattern(excludeDir, InputHistory.Bucket.EXCLUDE_DIR);
            inputHistory.remember(InputHistory.Bucket.EXTENSION, extensions.getText());
            extensions.setHistory(inputHistory.list(InputHistory.Bucket.EXTENSION));
            config.rememberExtensions(extensions.getText());
            try {
                config.save();
            } catch (IOException ignored) {
                // The search still runs. The recent list is kept for this session.
            }
            fillExtensionPresets();
        }
        controller.search(currentNames(), currentFilter());
    }

    private void renderBrowse() {
        model.setRows(browseRows);
        String source = switch (browseStatus.origin()) {
            case CACHE -> Messages.SOURCE_CACHE;
            case REFRESHING -> Messages.SOURCE_REFRESHING;
            case FILESYSTEM -> Messages.SOURCE_FS;
            case SEARCH -> Messages.SOURCE_SEARCH;
        };
        String detail = browseStatus.detail();
        String line = Messages.items(browseRows.size(), browseStatus.elapsedMillis(), source, detail);
        if (browseRows.isEmpty() && browseStatus.origin() != LoadStatus.Origin.REFRESHING) {
            line += " · " + Messages.EMPTY_FOLDER;
        }
        status.setText(line);
    }

    private ExtensionFilter currentFilter() {
        return ExtensionFilter.parse(extensions.getText(), includeFolders.isSelected());
    }

    private void applyExtensionPreset() {
        if (suppressExtensionEvents) {
            return;
        }
        String selected = extensionPreset.getSelectedItem() instanceof String text ? text : "";
        String value = presetValue(selected);
        if (value == null) {
            return;
        }
        suppressExtensionEvents = true;
        extensions.setText(value);
        suppressExtensionEvents = false;
    }

    private String presetValue(String selected) {
        if (selected == null || selected.equals(Messages.EXT_PRESET_ALL)) {
            return "";
        }
        for (String[] preset : EXTENSION_PRESETS) {
            if (preset[0].equals(selected)) {
                return preset[1];
            }
        }
        if (selected.startsWith(Messages.EXT_RECENT_PREFIX)) {
            return selected.substring(Messages.EXT_RECENT_PREFIX.length());
        }
        return null;
    }

    private void fillExtensionPresets() {
        java.awt.event.ActionListener[] listeners = extensionPreset.getActionListeners();
        for (java.awt.event.ActionListener listener : listeners) {
            extensionPreset.removeActionListener(listener);
        }
        extensionPreset.removeAllItems();
        for (String[] preset : EXTENSION_PRESETS) {
            extensionPreset.addItem(preset[0]);
        }
        for (String recent : config.recentExtensions()) {
            extensionPreset.addItem(Messages.EXT_RECENT_PREFIX + recent);
        }
        extensionPreset.setSelectedIndex(0);
        for (java.awt.event.ActionListener listener : listeners) {
            extensionPreset.addActionListener(listener);
        }
    }

    private static final String[][] EXTENSION_PRESETS = {
            {Messages.EXT_PRESET_ALL, ""},
            {Messages.EXT_PRESET_DOCS, "txt, md, pdf, doc, docx"},
            {Messages.EXT_PRESET_SHEETS, "csv, xls, xlsx"},
            {Messages.EXT_PRESET_IMAGES, "jpg, jpeg, png, gif, webp"},
            {Messages.EXT_PRESET_SOURCE, "java, xml, json, yml, properties"}
    };
}
