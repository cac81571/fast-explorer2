package com.fastexplorer.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeReaderTest {
    @TempDir
    Path temp;

    @Test
    void readsBasicAttributesForARealFile() throws Exception {
        Path file = temp.resolve("hello.txt");
        Files.writeString(file, "hello");
        AttributeReader.AttributeRead read = new AttributeReader().read(file);
        assertEquals(5, read.attributes().size());
        assertTrue(read.attributes().isRegularFile());
    }

    @Test
    void lookupUsesHolderCacheWhenTheJdkOpensIt() throws Exception {
        assertTrue(AttributeReader.cacheBridgeAvailable(),
                "start tests with --add-opens java.base/sun.nio.fs=ALL-UNNAMED");
        Path file = temp.resolve("cached.txt");
        Files.writeString(file, "cached");
        BasicFileAttributes real = Files.readAttributes(file, BasicFileAttributes.class);
        Class<?> holder = Class.forName("sun.nio.fs.BasicFileAttributesHolder");
        Object proxy = Proxy.newProxyInstance(
                holder.getClassLoader(),
                new Class<?>[]{holder},
                (p, method, args) -> switch (method.getName()) {
                    case "get" -> real;
                    case "invalidate" -> null;
                    default -> null;
                });
        assertSame(real, AttributeReader.lookupCached(proxy));
        assertNull(AttributeReader.lookupCached(file));
        assertNull(AttributeReader.lookupCached(null));
    }
}
