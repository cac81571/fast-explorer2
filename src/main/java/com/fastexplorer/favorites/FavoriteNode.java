package com.fastexplorer.favorites;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A group (nestable category) or a favorite folder. Groups have no path. Folders are leaves.
 */
public final class FavoriteNode {
    private final String id;
    private String name;
    private final boolean group;
    private final String path;
    private final List<FavoriteNode> children = new ArrayList<>();

    FavoriteNode(String id, String name, boolean group, String path) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.group = group;
        this.path = path;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean isGroup() {
        return group;
    }

    public String path() {
        return path;
    }

    public List<FavoriteNode> children() {
        return Collections.unmodifiableList(children);
    }

    void rename(String name) {
        this.name = name;
    }

    List<FavoriteNode> childList() {
        return children;
    }

    FavoriteNode copy() {
        FavoriteNode copy = new FavoriteNode(id, name, group, path);
        for (FavoriteNode child : children) {
            copy.children.add(child.copy());
        }
        return copy;
    }
}
