package com.fastexplorer.search;

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
import com.fastexplorer.model.SearchHit;
import com.fastexplorer.util.PathStrings;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Answers a name query from the H2 index first, then walks only the directories whose cache is
 * missing or expired and writes those listings back into the cache.
 */
public final class SearchService {
    private static final Logger LOG = Logger.getLogger("fastexplorer");

    public interface Listener {
        void onCacheHits(List<SearchHit> hits);

        /** Replaces every hit previously reported for {@code dirKey}. */
        void onDirectoryUpdated(String dirKey, List<SearchHit> matches);

        void onProgress(int scannedDirs, int errors);

        void onFinished(int scannedDirs, int errors, boolean cancelled);
    }

    private final ListingCache cache;
    private final DirectoryLister lister;
    private final ExecutorService blockingIo;
    private final ExplorerSettings settings;

    public SearchService(
            ListingCache cache,
            DirectoryLister lister,
            ExecutorService blockingIo,
            ExplorerSettings settings
    ) {
        this.cache = cache;
        this.lister = lister;
        this.blockingIo = blockingIo;
        this.settings = settings;
    }

    public void search(Path root, String query, BooleanSupplier cancelled, Listener listener) {
        search(root, query, cancelled, listener, ExtensionFilter.all());
    }

    public void search(
            Path root,
            String query,
            BooleanSupplier cancelled,
            Listener listener,
            ExtensionFilter filter
    ) {
        search(root, query, cancelled, listener, filter, true);
    }

    public void search(
            Path root,
            String query,
            BooleanSupplier cancelled,
            Listener listener,
            ExtensionFilter filter,
            boolean includeSubfolders
    ) {
        search(root, query, cancelled, listener, filter, includeSubfolders, true);
    }

    public void search(
            Path root,
            String query,
            BooleanSupplier cancelled,
            Listener listener,
            ExtensionFilter filter,
            boolean includeSubfolders,
            boolean useIndex
    ) {
        ExtensionFilter effective = filter == null ? ExtensionFilter.all() : filter;
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            listener.onFinished(0, 0, false);
            return;
        }
        String rootKey = PathStrings.cacheKey(root);
        List<SearchHit> initial = List.of();
        if (useIndex) {
            try {
                initial = cache.searchByName(rootKey, q, settings.searchMaxResults(), effective, includeSubfolders);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "index search failed", e);
                initial = List.of();
            }
        }
        if (cancelled.getAsBoolean()) {
            listener.onFinished(0, 0, true);
            return;
        }
        listener.onCacheHits(initial);

        ArrayDeque<Path> queue = new ArrayDeque<>();
        queue.add(root);
        Set<String> visited = new HashSet<>();
        int scanned = 0;
        int errors = 0;
        while (!queue.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                listener.onFinished(scanned, errors, true);
                return;
            }
            if (scanned >= settings.searchMaxDirectories()) {
                break;
            }
            Path dir = queue.removeFirst();
            String key = PathStrings.cacheKey(dir);
            if (!visited.add(key)) {
                continue;
            }
            scanned++;
            Visit visit = readDirectory(dir, key, cancelled, useIndex);
            if (visit.cancelled()) {
                listener.onFinished(scanned, errors, true);
                return;
            }
            if (visit.failed()) {
                errors++;
                listener.onProgress(scanned, errors);
                continue;
            }
            List<SearchHit> matches = new ArrayList<>();
            for (FileEntry child : visit.children()) {
                if (".".equals(child.name()) || "..".equals(child.name())) {
                    continue;
                }
                if (includeSubfolders && child.kind() == EntryKind.DIRECTORY) {
                    queue.addLast(dir.resolve(child.name()));
                }
                if (PathStrings.nameMatches(child.name(), q) && effective.accepts(child.name(), child.kind())) {
                    matches.add(new SearchHit(key, child.name(), child.kind(), child.size(), child.modifiedEpochMillis()));
                }
            }
            if (visit.refreshed()) {
                listener.onDirectoryUpdated(key, matches);
            }
            listener.onProgress(scanned, errors);
        }
        listener.onFinished(scanned, errors, cancelled.getAsBoolean());
    }

    /**
     * Same cache-then-walk search, with file and directory include/exclude patterns.
     * {@code exclude(dir)} is not entered, so folders such as {@code node_modules} are not traversed.
     */
    public void search(
            Path root,
            NameFilter names,
            BooleanSupplier cancelled,
            Listener listener,
            ExtensionFilter filter
    ) {
        search(root, names, cancelled, listener, filter, true);
    }

    public void search(
            Path root,
            NameFilter names,
            BooleanSupplier cancelled,
            Listener listener,
            ExtensionFilter filter,
            boolean includeSubfolders
    ) {
        search(root, names, cancelled, listener, filter, includeSubfolders, true);
    }

    public void search(
            Path root,
            NameFilter names,
            BooleanSupplier cancelled,
            Listener listener,
            ExtensionFilter filter,
            boolean includeSubfolders,
            boolean useIndex
    ) {
        NameFilter effectiveNames = names == null ? NameFilter.all() : names;
        ExtensionFilter effective = filter == null ? ExtensionFilter.all() : filter;
        String rootKey = PathStrings.cacheKey(root);
        List<SearchHit> initial = List.of();
        if (useIndex) {
            try {
                initial = cache.search(rootKey, effectiveNames, settings.searchMaxResults(), effective, includeSubfolders);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "index search failed", e);
                initial = List.of();
            }
        }
        if (cancelled.getAsBoolean()) {
            listener.onFinished(0, 0, true);
            return;
        }
        listener.onCacheHits(initial);

        ArrayDeque<Path> queue = new ArrayDeque<>();
        queue.add(root);
        Set<String> visited = new HashSet<>();
        int scanned = 0;
        int errors = 0;
        while (!queue.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                listener.onFinished(scanned, errors, true);
                return;
            }
            if (scanned >= settings.searchMaxDirectories()) {
                break;
            }
            Path dir = queue.removeFirst();
            String key = PathStrings.cacheKey(dir);
            if (!visited.add(key)) {
                continue;
            }
            scanned++;
            Visit visit = readDirectory(dir, key, cancelled, useIndex);
            if (visit.cancelled()) {
                listener.onFinished(scanned, errors, true);
                return;
            }
            if (visit.failed()) {
                errors++;
                listener.onProgress(scanned, errors);
                continue;
            }
            String relative = NameFilter.relative(rootKey, key);
            List<SearchHit> matches = new ArrayList<>();
            for (FileEntry child : visit.children()) {
                if (".".equals(child.name()) || "..".equals(child.name())) {
                    continue;
                }
                if (includeSubfolders && child.kind() == EntryKind.DIRECTORY) {
                    if (!effectiveNames.excludesDirectory(relative, child.name())) {
                        queue.addLast(dir.resolve(child.name()));
                    }
                }
                if (effectiveNames.accepts(relative, child.name(), child.kind())
                        && effective.accepts(child.name(), child.kind())) {
                    matches.add(new SearchHit(key, child.name(), child.kind(), child.size(), child.modifiedEpochMillis()));
                }
            }
            if (visit.refreshed()) {
                listener.onDirectoryUpdated(key, matches);
            }
            listener.onProgress(scanned, errors);
        }
        listener.onFinished(scanned, errors, cancelled.getAsBoolean());
    }

    private Visit readDirectory(Path dir, String key, BooleanSupplier cancelled, boolean useIndex) {
        DirectorySnapshot snapshot = null;
        if (useIndex) {
            try {
                snapshot = cache.find(key).orElse(null);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "cache read failed during search", e);
            }
        }
        CacheDecision decision = useIndex
                ? CacheDecision.decide(snapshot, System.currentTimeMillis(), settings.ttlMillis())
                : CacheDecision.MISS;
        if (decision == CacheDecision.FRESH && snapshot != null) {
            return Visit.fresh(snapshot.entries());
        }
        try {
            long deadline = System.currentTimeMillis() + settings.timeoutMillis();
            ListResult result = IoGuard.call(
                    blockingIo,
                    () -> lister.list(dir, cancelled, deadline),
                    settings.timeoutMillis(),
                    cancelled);
            if (!result.complete()) {
                return Visit.stopped();
            }
            try {
                cache.replace(key, result.entries(), System.currentTimeMillis(), true);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "cache write failed during search", e);
            }
            return Visit.updated(result.entries());
        } catch (ListingException e) {
            if (e.kind() == ListingException.Kind.CANCELLED) {
                return Visit.stopped();
            }
            LOG.log(Level.FINE, "skip directory during search: " + dir, e);
            if (decision == CacheDecision.STALE && snapshot != null) {
                return Visit.fresh(snapshot.entries());
            }
            return Visit.unreadable();
        }
    }

    private record Visit(List<FileEntry> children, boolean refreshed, boolean failed, boolean cancelled) {
        static Visit fresh(List<FileEntry> children) {
            return new Visit(children, false, false, false);
        }

        static Visit updated(List<FileEntry> children) {
            return new Visit(children, true, false, false);
        }

        static Visit unreadable() {
            return new Visit(List.of(), false, true, false);
        }

        static Visit stopped() {
            return new Visit(List.of(), false, false, true);
        }
    }
}
