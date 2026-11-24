package com.fastexplorer.favorites;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FavoriteTreeTest {
    @Test
    void buildsNestedGroupsAndFolders() {
        FavoriteTree tree = sample();
        assertEquals(List.of("仕事", "サーバー"), tree.roots().stream().map(FavoriteNode::name).toList());
        FavoriteNode work = tree.roots().get(0);
        assertEquals(List.of("仕様書", "見積"), work.children().stream().map(FavoriteNode::name).toList());
        assertEquals("\\\\server\\share\\docs", work.children().get(0).path());
        assertTrue(tree.groups().stream().map(FavoriteTree.GroupRef::breadcrumb).toList()
                .contains("サーバー / 2026年度"));
    }

    @Test
    void renameRemoveAndDuplicatePath() {
        FavoriteTree tree = sample();
        FavoriteNode work = tree.roots().get(0);
        assertTrue(tree.rename(work.id(), " 仕事用 "));
        assertEquals("仕事用", tree.find(work.id()).name());
        assertFalse(tree.rename(work.id(), "  "));
        assertFalse(tree.rename("missing", "x"));

        FavoriteNode again = tree.addFolder(work.id(), "別名", "\\\\server\\share\\docs");
        assertSame(work.children().get(0), again);
        assertEquals(2, work.children().size());

        assertTrue(tree.remove(work.id()));
        assertNull(tree.find(work.id()));
        assertEquals(List.of("サーバー"), tree.roots().stream().map(FavoriteNode::name).toList());
    }

    @Test
    void reordersAndMovesBetweenGroups() {
        FavoriteTree tree = sample();
        FavoriteNode work = tree.roots().get(0);
        FavoriteNode estimate = work.children().get(1);
        assertTrue(tree.move(estimate.id(), work.id(), 0));
        assertEquals(List.of("見積", "仕様書"), work.children().stream().map(FavoriteNode::name).toList());

        FavoriteNode server = tree.roots().get(1);
        assertTrue(tree.move(estimate.id(), server.id(), 0));
        assertEquals(List.of("仕様書"), work.children().stream().map(FavoriteNode::name).toList());
        assertEquals("見積", server.children().get(0).name());

        FavoriteNode year = server.children().stream().filter(FavoriteNode::isGroup).findFirst().orElseThrow();
        assertFalse(tree.move(server.id(), year.id(), 0));
        assertFalse(tree.move(estimate.id(), work.children().get(0).id(), 0));
        assertEquals("見積", tree.find(estimate.id()).name());
    }

    @Test
    void filterKeepsAncestorsAndHidesUnmatchedSiblings() {
        FavoriteTree tree = sample();
        FavoriteTree narrowed = tree.filtered("案件");
        assertEquals(List.of("サーバー"), names(narrowed.roots()));
        FavoriteNode server = narrowed.roots().get(0);
        assertEquals(List.of("2026年度"), names(server.children()));
        assertEquals(List.of("案件A"), names(server.children().get(0).children()));

        FavoriteTree pathHit = tree.filtered("LOGS");
        assertEquals(List.of("サーバー"), names(pathHit.roots()));
        assertEquals(List.of("logs"), names(pathHit.roots().get(0).children()));

        FavoriteTree groupHit = tree.filtered("仕事");
        assertEquals(List.of("仕様書", "見積"), names(groupHit.roots().get(0).children()));

        assertTrue(tree.filtered("ない").roots().isEmpty());
        assertEquals(names(tree.roots()), names(tree.filtered("  ").roots()));
    }

    @Test
    void displayNameUsesTheLastSegmentOfUncAndLocalPaths() {
        assertEquals("docs", FavoriteNames.displayName("\\\\server\\share\\docs"));
        assertEquals("docs", FavoriteNames.displayName("//server/share/docs/"));
        assertEquals("仕様書", FavoriteNames.displayName("/tmp/fast-explorer-demo/仕様書"));
        assertEquals("data", FavoriteNames.displayName("D:\\data"));
    }

    private static FavoriteTree sample() {
        FavoriteTree tree = new FavoriteTree();
        FavoriteNode work = tree.addGroup(null, "仕事");
        tree.addFolder(work.id(), "仕様書", "\\\\server\\share\\docs");
        tree.addFolder(work.id(), "見積", "D:\\estimates");
        FavoriteNode server = tree.addGroup(null, "サーバー");
        tree.addFolder(server.id(), "logs", "\\\\server\\share\\logs");
        FavoriteNode year = tree.addGroup(server.id(), "2026年度");
        tree.addFolder(year.id(), "案件A", "\\\\server\\share\\projects\\2026");
        return tree;
    }

    private static List<String> names(List<FavoriteNode> nodes) {
        return nodes.stream().map(FavoriteNode::name).toList();
    }
}
