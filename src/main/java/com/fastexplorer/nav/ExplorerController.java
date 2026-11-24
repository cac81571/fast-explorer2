package com.fastexplorer.nav;

import com.fastexplorer.Messages;
import com.fastexplorer.cache.CacheDecision;
import com.fastexplorer.cache.ListingCache;
import com.fastexplorer.config.ExplorerSettings;
import com.fastexplorer.fs.DirectoryLister;
import com.fastexplorer.fs.IoGuard;
import com.fastexplorer.fs.ListingException;
import com.fastexplorer.model.DirectorySnapshot;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.ListResult;
import com.fastexplorer.model.LoadStatus;
import com.fastexplorer.model.SearchHit;
import com.fastexplorer.search.ExtensionFilter;
import com.fastexplorer.search.NameFilter;
import com.fastexplorer.search.SearchService;
import com.fastexplorer.util.PathStrings;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Navigation, stale-while-revalidate listings, and search. Every filesystem and database call
 * runs on a background executor. {@link Listener} is invoked on {@code edt}.
 */
public final class ExplorerController {
    private static final Logger LOG = Logger.getLogger("fastexplorer");

    public interface Listener {
        void onLoadStarted(Path directory);

        void onLocation(String displayPath, boolean canBack, boolean canForward, boolean canUp);

        void onListing(Path directory, List<FileEntry> entries, LoadStatus status);

        /** @param keepRows when true, the current table stays (stale cache or a file-open error) */
        void onError(String message, boolean keepRows);

        void onSearchStarted(String query);

        void onSearchHits(List<SearchHit> cacheHits, long elapsedMillis);

        void onSearchDirectory(String dirKey, List<SearchHit> matches, long elapsedMillis);

        void onSearchProgress(int scannedDirs, int errors, long elapsedMillis);

        void onSearchFinished(int scannedDirs, int errors, long elapsedMillis);
    }

    private final DirectoryLister lister;
    private final ListingCache cache;
    private final ExplorerSettings settings;
    private final ExecutorService tasks;
    private final ExecutorService blockingIo;
    private final Consumer<Runnable> edt;
    private final Listener listener;
    private final AtomicLong requestSeq = new AtomicLong();
    private final Object historyLock = new Object();
    private final List<Path> history = new ArrayList<>();

    private int historyIndex = -1;
    private volatile Path current;
    private volatile String pendingSelection;

    public ExplorerController(
            DirectoryLister lister,
            ListingCache cache,
            ExplorerSettings settings,
            Consumer<Runnable> edt,
            Listener listener
    ) {
        this.lister = lister;
        this.cache = cache;
        this.settings = settings;
        this.edt = edt;
        this.listener = listener;
        this.tasks = Executors.newCachedThreadPool(r -> daemon(r, "fast-explorer-task"));
        this.blockingIo = Executors.newCachedThreadPool(r -> daemon(r, "fast-explorer-io"));
    }

    public void open(Path path, boolean recordHistory, boolean force) {
        Path normalized = normalize(path);
        synchronized (historyLock) {
            if (recordHistory) {
                if (historyIndex >= 0 && historyIndex < history.size() - 1) {
                    history.subList(historyIndex + 1, history.size()).clear();
                }
                if (!(historyIndex >= 0 && same(history.get(historyIndex), normalized))) {
                    history.add(normalized);
                    historyIndex = history.size() - 1;
                }
            }
            current = normalized;
            pendingSelection = null;
        }
        begin(normalized, force);
    }

    public void back() {
        Path target;
        synchronized (historyLock) {
            if (historyIndex <= 0) {
                return;
            }
            historyIndex--;
            target = history.get(historyIndex);
            current = target;
            pendingSelection = null;
        }
        begin(target, false);
    }

    public void forward() {
        Path target;
        synchronized (historyLock) {
            if (historyIndex < 0 || historyIndex >= history.size() - 1) {
                return;
            }
            historyIndex++;
            target = history.get(historyIndex);
            current = target;
            pendingSelection = null;
        }
        begin(target, false);
    }

    public void up() {
        Path cur = current;
        if (cur == null || cur.getParent() == null) {
            return;
        }
        open(cur.getParent(), true, false);
    }

    /** Reload the current folder, still painting a fresh cache before the new listing replaces it. */
    public void refresh() {
        Path cur = current;
        if (cur == null) {
            return;
        }
        synchronized (historyLock) {
            pendingSelection = null;
        }
        begin(cur, true);
    }

    public void search(String query) {
        search(query, ExtensionFilter.all());
    }

    public void search(String query, ExtensionFilter filter) {
        if (query == null || query.isBlank()) {
            return;
        }
        search(NameFilter.parse(query, "", "", ""), filter);
    }

    public void search(NameFilter names, ExtensionFilter filter) {
        Path root = current;
        if (root == null || names == null) {
            return;
        }
        ExtensionFilter effective = filter == null ? ExtensionFilter.all() : filter;
        long req = requestSeq.incrementAndGet();
        long start = System.nanoTime();
        edt.accept(() -> listener.onSearchStarted(names.hasFilePatterns() || names.hasDirectoryPatterns() ? "pattern" : ""));
        tasks.submit(() -> runSearch(root, names, effective, req, start));
    }

    /** Cancel the in-flight listing or search and show the current folder again. */
    public void cancelAndReload() {
        Path cur = current;
        if (cur == null) {
            requestSeq.incrementAndGet();
            return;
        }
        begin(cur, false);
    }

    public void activate(Path path, EntryKind kind) {
        if (kind == EntryKind.DIRECTORY) {
            open(path, true, false);
            return;
        }
        tasks.submit(() -> {
            if (kind == EntryKind.SYMLINK) {
                try {
                    boolean directory = IoGuard.call(
                            blockingIo,
                            () -> Files.isDirectory(path),
                            settings.timeoutMillis(),
                            () -> false);
                    if (directory) {
                        open(path, true, false);
                        return;
                    }
                } catch (ListingException e) {
                    if (e.kind() != ListingException.Kind.CANCELLED) {
                        reportError(requestSeq.get(), e.getMessage(), true);
                    }
                    return;
                }
            }
            openWithDesktop(path);
        });
    }

    public String pendingSelection() {
        return pendingSelection;
    }

    public Path currentPath() {
        return current;
    }

    public void shutdown() {
        requestSeq.incrementAndGet();
        tasks.shutdownNow();
        blockingIo.shutdownNow();
    }

    private void begin(Path path, boolean force) {
        long req = requestSeq.incrementAndGet();
        long start = System.nanoTime();
        publishLocation(path);
        edt.accept(() -> listener.onLoadStarted(path));
        tasks.submit(() -> load(path, req, force, start));
    }

    private void load(Path path, long req, boolean force, long startNano) {
        String key = PathStrings.cacheKey(path);
        DirectorySnapshot snapshot = readSnapshot(key);
        CacheDecision decision = CacheDecision.decide(snapshot, System.currentTimeMillis(), settings.ttlMillis());
        if (force && decision == CacheDecision.FRESH) {
            decision = CacheDecision.STALE;
        }
        if ((decision == CacheDecision.FRESH || decision == CacheDecision.STALE) && snapshot != null) {
            LoadStatus.Origin origin = decision == CacheDecision.STALE
                    ? LoadStatus.Origin.REFRESHING
                    : LoadStatus.Origin.CACHE;
            publishListing(path, req, snapshot.entries(), elapsed(startNano), origin, "");
            if (decision == CacheDecision.FRESH) {
                return;
            }
        }
        if (!isCurrent(req)) {
            return;
        }
        try {
            long timeout = settings.timeoutMillis();
            ListResult result = IoGuard.call(
                    blockingIo,
                    () -> lister.list(path, () -> !isCurrent(req), System.currentTimeMillis() + timeout),
                    timeout,
                    () -> !isCurrent(req));
            if (!isCurrent(req) || !result.complete()) {
                return;
            }
            try {
                cache.replace(key, result.entries(), System.currentTimeMillis(), true);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "cache write failed", e);
            }
            String detail = result.attributeCacheHits() > 0
                    ? Messages.attributeStats(result.attributeCacheHits(), result.attributeReads())
                    : "";
            publishListing(path, req, result.entries(), elapsed(startNano), LoadStatus.Origin.FILESYSTEM, detail);
        } catch (ListingException e) {
            if (e.kind() == ListingException.Kind.CANCELLED || !isCurrent(req)) {
                return;
            }
            if (e.kind() == ListingException.Kind.NOT_DIRECTORY) {
                redirectFileToParent(path, req);
                return;
            }
            boolean keepRows = decision == CacheDecision.STALE;
            String message = keepRows ? Messages.ERR_REFRESH_PREFIX + e.getMessage() : e.getMessage();
            reportError(req, message, keepRows);
        }
    }

    private void redirectFileToParent(Path file, long req) {
        Path parent = file.getParent();
        if (parent == null || same(parent, file) || !isCurrent(req)) {
            reportError(req, Messages.ERR_NOT_DIR, false);
            return;
        }
        String name = file.getFileName() == null ? null : file.getFileName().toString();
        synchronized (historyLock) {
            if (historyIndex >= 0 && historyIndex < history.size() && same(history.get(historyIndex), file)) {
                history.set(historyIndex, normalize(parent));
            }
            current = normalize(parent);
            pendingSelection = name;
        }
        begin(normalize(parent), false);
    }

    private void runSearch(Path root, NameFilter names, ExtensionFilter filter, long req, long startNano) {
        SearchService service = new SearchService(cache, lister, blockingIo, settings);
        try {
            service.search(root, names, () -> !isCurrent(req), new SearchService.Listener() {
                @Override
                public void onCacheHits(List<SearchHit> hits) {
                    if (!isCurrent(req)) {
                        return;
                    }
                    long elapsed = elapsed(startNano);
                    edt.accept(() -> {
                        if (isCurrent(req)) {
                            listener.onSearchHits(hits, elapsed);
                        }
                    });
                }

                @Override
                public void onDirectoryUpdated(String dirKey, List<SearchHit> matches) {
                    if (!isCurrent(req)) {
                        return;
                    }
                    long elapsed = elapsed(startNano);
                    edt.accept(() -> {
                        if (isCurrent(req)) {
                            listener.onSearchDirectory(dirKey, matches, elapsed);
                        }
                    });
                }

                @Override
                public void onProgress(int scannedDirs, int errors) {
                    if (!isCurrent(req)) {
                        return;
                    }
                    long elapsed = elapsed(startNano);
                    edt.accept(() -> {
                        if (isCurrent(req)) {
                            listener.onSearchProgress(scannedDirs, errors, elapsed);
                        }
                    });
                }

                @Override
                public void onFinished(int scannedDirs, int errors, boolean cancelled) {
                    if (!isCurrent(req) || cancelled) {
                        return;
                    }
                    long elapsed = elapsed(startNano);
                    edt.accept(() -> {
                        if (!isCurrent(req)) {
                            return;
                        }
                        listener.onSearchFinished(scannedDirs, errors, elapsed);
                        if (errors > 0) {
                            listener.onError(Messages.partialRead(errors), true);
                        }
                    });
                }
            }, filter);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "search failed", e);
            reportError(req, Messages.ERR_IO + ": " + e.getMessage(), true);
        }
    }

    private void openWithDesktop(Path path) {
        long req = requestSeq.get();
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                reportError(req, Messages.ERR_DESKTOP, true);
                return;
            }
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            reportError(req, Messages.ERR_OPEN_FILE + ": " + detail, true);
        }
    }

    private DirectorySnapshot readSnapshot(String key) {
        try {
            Optional<DirectorySnapshot> found = cache.find(key);
            return found.orElse(null);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cache read failed", e);
            return null;
        }
    }

    private void publishListing(
            Path path,
            long req,
            List<FileEntry> entries,
            long elapsedMillis,
            LoadStatus.Origin origin,
            String detail
    ) {
        if (!isCurrent(req)) {
            return;
        }
        List<FileEntry> copy = List.copyOf(entries);
        LoadStatus status = new LoadStatus(copy.size(), elapsedMillis, origin, detail);
        edt.accept(() -> {
            if (isCurrent(req)) {
                listener.onListing(path, copy, status);
            }
        });
    }

    private void publishLocation(Path path) {
        boolean back;
        boolean forward;
        boolean up;
        synchronized (historyLock) {
            back = historyIndex > 0;
            forward = historyIndex >= 0 && historyIndex < history.size() - 1;
            up = path.getParent() != null;
        }
        String display = path.toString();
        edt.accept(() -> listener.onLocation(display, back, forward, up));
    }

    private void reportError(long req, String message, boolean keepRows) {
        if (!isCurrent(req)) {
            return;
        }
        edt.accept(() -> {
            if (isCurrent(req)) {
                listener.onError(message, keepRows);
            }
        });
    }

    private boolean isCurrent(long req) {
        return requestSeq.get() == req;
    }

    private boolean same(Path a, Path b) {
        return PathStrings.cacheKey(a).equals(PathStrings.cacheKey(b));
    }

    private static Path normalize(Path path) {
        Path normalized = path.normalize();
        String raw = normalized.toString();
        if (!PathStrings.isUnc(raw) && !normalized.isAbsolute()) {
            normalized = normalized.toAbsolutePath().normalize();
        }
        return normalized;
    }

    private static long elapsed(long startNano) {
        return Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
