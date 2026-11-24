package com.fastexplorer.fs;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads {@link BasicFileAttributes} for one directory entry.
 *
 * <p>On Windows, {@code WindowsDirectoryStream} attaches the {@code WIN32_FIND_DATA} from
 * {@code FindNextFile} to the {@code Path} via the internal {@code BasicFileAttributesHolder}.
 * {@code Files.readAttributes} does not consult that cache, so we read it ourselves when the
 * package is opened ({@code --add-opens java.base/sun.nio.fs=ALL-UNNAMED}). The first entry
 * returned by {@code FindFirstFile} is published without attributes; that one falls back to
 * {@code readAttributes}. Directories and files both use this single attribute object. Size is
 * recorded only for regular files by the caller.
 */
public final class AttributeReader {
    private static final Logger LOG = Logger.getLogger("fastexplorer");
    private static final Method CACHED_GET = resolveCachedGetter();

    public record AttributeRead(BasicFileAttributes attributes, boolean fromCache) {
    }

    public static boolean cacheBridgeAvailable() {
        return CACHED_GET != null;
    }

    public AttributeRead read(Path path) throws IOException {
        BasicFileAttributes cached = lookupCached(path);
        if (cached != null) {
            return new AttributeRead(cached, true);
        }
        BasicFileAttributes attrs = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new AttributeRead(attrs, false);
    }

    public static BasicFileAttributes lookupCached(Object entry) {
        if (CACHED_GET == null || entry == null) {
            return null;
        }
        if (!CACHED_GET.getDeclaringClass().isInstance(entry)) {
            return null;
        }
        try {
            return (BasicFileAttributes) CACHED_GET.invoke(entry);
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOG.log(Level.FINE, "cached attribute read failed", e);
            return null;
        }
    }

    private static Method resolveCachedGetter() {
        try {
            Class<?> holder = Class.forName("sun.nio.fs.BasicFileAttributesHolder");
            Method method = holder.getMethod("get");
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "BasicFileAttributesHolder is not accessible; using readAttributes", t);
            return null;
        }
    }
}
