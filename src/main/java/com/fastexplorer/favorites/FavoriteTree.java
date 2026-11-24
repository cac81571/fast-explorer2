package com.fastexplorer.favorites;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutable favorites document. Folders are leaves. Groups may contain groups and folders.
 * {@link #filtered(String)} returns a copy that keeps ancestor groups of every match.
 */
public final class FavoriteTree {
    private final List<FavoriteNode> roots = new ArrayList<>();

    public List<FavoriteNode> roots() {
        return List.copyOf(roots);
    }

    public FavoriteNode addGroup(String parentId, String name) {
        FavoriteNode parent = parentGroup(parentId);
        if (parentId != null && parent == null) {
            return null;
        }
        FavoriteNode node = new FavoriteNode(newId(), cleanName(name), true, null);
        childrenOf(parent).add(node);
        return node;
    }

    /**
     * Adds a folder. The same path under the same parent is not duplicated; the existing node is returned.
     */
    public FavoriteNode addFolder(String parentId, String name, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path");
        }
        FavoriteNode parent = parentGroup(parentId);
        if (parentId != null && parent == null) {
            return null;
        }
        String stored = path.trim();
        for (FavoriteNode child : childrenOf(parent)) {
            if (!child.isGroup() && stored.equals(child.path())) {
                return child;
            }
        }
        FavoriteNode node = new FavoriteNode(newId(), cleanName(name), false, stored);
        childrenOf(parent).add(node);
        return node;
    }

    public boolean rename(String id, String name) {
        FavoriteNode node = find(id);
        if (node == null) {
            return false;
        }
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty()) {
            return false;
        }
        node.rename(cleaned);
        return true;
    }

    public boolean remove(String id) {
        FavoriteNode node = find(id);
        if (node == null) {
            return false;
        }
        return childrenOf(parentOf(node)).remove(node);
    }

    /**
     * Moves {@code id} under {@code newParentId} (null is the root) at {@code index}.
     * Returns false when the destination is a folder, missing, or inside the moved subtree.
     */
    public boolean move(String id, String newParentId, int index) {
        FavoriteNode node = find(id);
        if (node == null) {
            return false;
        }
        FavoriteNode newParent = null;
        if (newParentId != null) {
            newParent = find(newParentId);
            if (newParent == null || !newParent.isGroup()) {
                return false;
            }
            if (newParent == node || contains(node, newParent)) {
                return false;
            }
        }
        List<FavoriteNode> from = childrenOf(parentOf(node));
        int oldIndex = from.indexOf(node);
        if (oldIndex < 0) {
            return false;
        }
        from.remove(oldIndex);
        List<FavoriteNode> to = childrenOf(newParent);
        if (from == to && index > oldIndex) {
            index--;
        }
        if (index < 0) {
            index = 0;
        }
        if (index > to.size()) {
            index = to.size();
        }
        to.add(index, node);
        return true;
    }

    public FavoriteNode find(String id) {
        if (id == null) {
            return null;
        }
        return findIn(roots, id);
    }

    public String parentId(String id) {
        FavoriteNode parent = parentOf(find(id));
        return parent == null ? null : parent.id();
    }

    public int indexInParent(String id) {
        FavoriteNode node = find(id);
        if (node == null) {
            return -1;
        }
        return childrenOf(parentOf(node)).indexOf(node);
    }

    /** Groups in tree order, each with a breadcrumb such as {@code 仕事 / 2026年度}. */
    public List<GroupRef> groups() {
        List<GroupRef> refs = new ArrayList<>();
        collectGroups(roots, "", refs);
        return refs;
    }

    /**
     * A view of this tree narrowed by {@code query}. Matches display name and path, case-insensitively.
     * Ancestor groups of a match stay visible. A matching group keeps its whole subtree.
     * A blank query returns a full copy.
     */
    public FavoriteTree filtered(String query) {
        FavoriteTree view = new FavoriteTree();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            for (FavoriteNode root : roots) {
                view.roots.add(root.copy());
            }
            return view;
        }
        for (FavoriteNode root : roots) {
            FavoriteNode kept = filterNode(root, needle);
            if (kept != null) {
                view.roots.add(kept);
            }
        }
        return view;
    }

    public void replaceWith(FavoriteTree other) {
        roots.clear();
        for (FavoriteNode root : other.roots) {
            roots.add(root.copy());
        }
    }

    void addCopy(FavoriteNode node) {
        roots.add(node);
    }

    private static FavoriteNode filterNode(FavoriteNode node, String needle) {
        if (matches(node, needle)) {
            return node.copy();
        }
        if (!node.isGroup()) {
            return null;
        }
        FavoriteNode copy = new FavoriteNode(node.id(), node.name(), true, null);
        for (FavoriteNode child : node.children()) {
            FavoriteNode kept = filterNode(child, needle);
            if (kept != null) {
                copy.childList().add(kept);
            }
        }
        return copy.childList().isEmpty() ? null : copy;
    }

    private static boolean matches(FavoriteNode node, String needle) {
        if (node.name().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return node.path() != null && node.path().toLowerCase(Locale.ROOT).contains(needle);
    }

    private FavoriteNode parentGroup(String parentId) {
        if (parentId == null) {
            return null;
        }
        FavoriteNode parent = find(parentId);
        if (parent == null || !parent.isGroup()) {
            return null;
        }
        return parent;
    }

    private List<FavoriteNode> childrenOf(FavoriteNode parent) {
        return parent == null ? roots : parent.childList();
    }

    private FavoriteNode parentOf(FavoriteNode target) {
        if (target == null) {
            return null;
        }
        return parentIn(roots, target);
    }

    private static FavoriteNode parentIn(List<FavoriteNode> nodes, FavoriteNode target) {
        for (FavoriteNode node : nodes) {
            if (node.childList().contains(target)) {
                return node;
            }
            FavoriteNode nested = parentIn(node.childList(), target);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static FavoriteNode findIn(List<FavoriteNode> nodes, String id) {
        for (FavoriteNode node : nodes) {
            if (node.id().equals(id)) {
                return node;
            }
            FavoriteNode nested = findIn(node.childList(), id);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static boolean contains(FavoriteNode ancestor, FavoriteNode candidate) {
        for (FavoriteNode child : ancestor.childList()) {
            if (child == candidate || contains(child, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static void collectGroups(List<FavoriteNode> nodes, String prefix, List<GroupRef> out) {
        for (FavoriteNode node : nodes) {
            if (!node.isGroup()) {
                continue;
            }
            String label = prefix.isEmpty() ? node.name() : prefix + " / " + node.name();
            out.add(new GroupRef(node.id(), label));
            collectGroups(node.childList(), label, out);
        }
    }

    private static String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? "名称未設定" : trimmed;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    public record GroupRef(String id, String breadcrumb) {
        public GroupRef {
            Objects.requireNonNull(id);
            Objects.requireNonNull(breadcrumb);
        }
    }
}
