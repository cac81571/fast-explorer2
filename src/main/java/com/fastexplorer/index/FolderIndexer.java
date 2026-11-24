package com.fastexplorer.index;

import com.fastexplorer.cache.ListingCache;
import com.fastexplorer.fs.DirectoryLister;
import com.fastexplorer.fs.ListingException;
import com.fastexplorer.model.EntryKind;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.ListResult;
import com.fastexplorer.search.NameFilter;
import com.fastexplorer.util.PathStrings;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Walks one folder tree on a single background thread and stores each directory listing
 * in the search cache. Overlapping roots are refused. Inaccessible folders are skipped.
 */
public final class FolderIndexer {
    private static final Logger LOG = Logger.getLogger("fastexplorer");
    private final DirectoryLister lister;
    private final ListingCache cache;
    private final IndexCatalog catalog;
    private final ExecutorService worker;
    private final ConcurrentHashMap<String, Job> running = new ConcurrentHashMap<>();
    private volatile Consumer<IndexRecord> listener = record -> {
    };

    public FolderIndexer(DirectoryLister lister, ListingCache cache, IndexCatalog catalog) {
        this(lister, cache, catalog, Executors.newFixedThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "fast-explorer-index");
            thread.setDaemon(true);
            return thread;
        }));
    }

    FolderIndexer(DirectoryLister lister, ListingCache cache, IndexCatalog catalog, ExecutorService worker) {
        this.lister = lister;
        this.cache = cache;
        this.catalog = catalog;
        this.worker = worker;
    }

    public void setListener(Consumer<IndexRecord> listener) {
        this.listener = listener == null ? record -> {
        } : listener;
    }

    /** @param maxDepth 0 means no limit. A positive value is how many levels below the root to enter. */
    public boolean start(Path root, int maxDepth) {
        return start(root, maxDepth, NameFilter.all());
    }

    /**
     * @param names directory excludes are not entered. File patterns do not change the walk.
     */
    public boolean start(Path root, int maxDepth, NameFilter names) {
        NameFilter filter = names == null ? NameFilter.all() : names;
        String key = PathStrings.cacheKey(root);
        if (overlapsRunning(key)) {
            return false;
        }
        Job job = new Job();
        if (running.putIfAbsent(key, job) != null) {
            return false;
        }
        if (overlapsOther(key)) {
            running.remove(key, job);
            return false;
        }
        worker.submit(() -> {
            try {
                walk(root, key, maxDepth, job, filter);
            } finally {
                running.remove(key, job);
            }
        });
        return true;
    }

    public boolean hasRunning() {
        return !running.isEmpty();
    }

    public void cancel(String rootKey) {
        Job job = running.get(rootKey);
        if (job != null) {
            job.cancelled = true;
        }
    }

    public void cancelAll() {
        for (Job job : running.values()) {
            job.cancelled = true;
        }
    }

    public void shutdown() {
        cancelAll();
        worker.shutdownNow();
    }

    static boolean overlaps(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        String leftPrefix = left.endsWith("/") ? left : left + "/";
        String rightPrefix = right.endsWith("/") ? right : right + "/";
        return left.startsWith(rightPrefix) || right.startsWith(leftPrefix);
    }

    private boolean overlapsRunning(String key) {
        for (String active : running.keySet()) {
            if (overlaps(key, active)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlapsOther(String key) {
        for (var entry : running.entrySet()) {
            if (!entry.getKey().equals(key) && overlaps(key, entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private void walk(Path root, String rootKey, int maxDepth, Job job, NameFilter names) {
        long started = System.currentTimeMillis();
        int folders = 0;
        int files = 0;
        int errors = 0;
        ArrayDeque<Level> queue = new ArrayDeque<>();
        queue.add(new Level(root, 0));
        Set<String> seen = new HashSet<>();
        publish(record(root, rootKey, IndexRecord.Status.RUNNING, started, 0, 0, 0, 0, 0, "", maxDepth));
        while (!queue.isEmpty()) {
            if (job.cancelled) {
                publish(record(root, rootKey, IndexRecord.Status.CANCELLED, started, System.currentTimeMillis(),
                        folders, files, System.currentTimeMillis() - started, errors, "", maxDepth));
                return;
            }
            Level level = queue.removeFirst();
            String key = PathStrings.cacheKey(level.path());
            if (!seen.add(key)) {
                continue;
            }
            try {
                long deadline = System.currentTimeMillis() + 120_000L;
                ListResult listed = lister.list(level.path(), () -> job.cancelled, deadline);
                if (!listed.complete() || job.cancelled) {
                    publish(record(root, rootKey, IndexRecord.Status.CANCELLED, started, System.currentTimeMillis(),
                            folders, files, System.currentTimeMillis() - started, errors, "", maxDepth));
                    return;
                }
                try {
                    cache.replace(key, listed.entries(), System.currentTimeMillis(), true);
                } catch (RuntimeException e) {
                    LOG.log(java.util.logging.Level.WARNING, "cache write failed during index: " + key, e);
                }
                folders++;
                String relative = NameFilter.relative(rootKey, key);
                for (FileEntry entry : listed.entries()) {
                    if (entry.kind() == EntryKind.DIRECTORY) {
                        if (names.excludesDirectory(relative, entry.name())) {
                            continue;
                        }
                        int childDepth = level.depth() + 1;
                        if (maxDepth <= 0 || childDepth <= maxDepth) {
                            queue.addLast(new Level(level.path().resolve(entry.name()), childDepth));
                        }
                    } else {
                        files++;
                    }
                }
                publish(record(root, rootKey, IndexRecord.Status.RUNNING, started, 0,
                        folders, files, System.currentTimeMillis() - started, errors, "", maxDepth));
            } catch (ListingException e) {
                if (e.kind() == ListingException.Kind.CANCELLED || job.cancelled) {
                    publish(record(root, rootKey, IndexRecord.Status.CANCELLED, started, System.currentTimeMillis(),
                            folders, files, System.currentTimeMillis() - started, errors, "", maxDepth));
                    return;
                }
                errors++;
                if (level.path().equals(root)) {
                    publish(record(root, rootKey, IndexRecord.Status.ERROR, started, System.currentTimeMillis(),
                            folders, files, System.currentTimeMillis() - started, errors,
                            e.getMessage() == null ? "" : e.getMessage(), maxDepth));
                    return;
                }
            } catch (RuntimeException e) {
                errors++;
                if (level.path().equals(root)) {
                    publish(record(root, rootKey, IndexRecord.Status.ERROR, started, System.currentTimeMillis(),
                            folders, files, System.currentTimeMillis() - started, errors,
                            e.getMessage() == null ? "" : e.getMessage(), maxDepth));
                    return;
                }
            }
        }
        publish(record(root, rootKey, IndexRecord.Status.DONE, started, System.currentTimeMillis(),
                folders, files, System.currentTimeMillis() - started, errors, "", maxDepth));
    }

    private void publish(IndexRecord record) {
        catalog.save(record);
        listener.accept(record);
    }

    private static IndexRecord record(
            Path root,
            String rootKey,
            IndexRecord.Status status,
            long started,
            long finished,
            int folders,
            int files,
            long duration,
            int errors,
            String message,
            int maxDepth
    ) {
        return new IndexRecord(rootKey, root.toString(), status, started, finished, folders, files, duration, errors, message, maxDepth);
    }

    private static final class Job {
        private volatile boolean cancelled;
    }

    private record Level(Path path, int depth) {
    }
}
