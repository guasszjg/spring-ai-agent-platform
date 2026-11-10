package com.shimeta.rag.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerContextTitleTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void titlesFollowTheHeadingPathAtEachChunk() {
        String text = "# 1 系统概述\n" + "概述正文。".repeat(80) + "\n"
                + "# 2 系统功能详解\n## 2.1 系统登录\n" + "登录步骤说明。".repeat(80) + "\n"
                + "## 2.2 素材管理\n### 2.2.1 素材列表\n" + "素材列表说明。".repeat(80);
        List<DocumentChunker.ParentChildPiece> pieces = chunker.splitParentChild(text);
        List<String> titles = chunker.contextTitles(text, pieces, "数字标牌手册");

        assertThat(titles).hasSameSizeAs(pieces);
        for (int i = 0; i < pieces.size(); i++) {
            String content = pieces.get(i).childContent();
            if (content.startsWith("概述正文")) {
                assertThat(titles.get(i)).isEqualTo("数字标牌手册 › 系统概述");
            } else if (content.startsWith("登录步骤")) {
                assertThat(titles.get(i)).isEqualTo("数字标牌手册 › 系统功能详解 › 系统登录");
            } else if (content.startsWith("素材列表说明")) {
                assertThat(titles.get(i)).isEqualTo("数字标牌手册 › 系统功能详解 › 素材管理 › 素材列表");
            }
        }
        assertThat(titles).contains("数字标牌手册 › 系统功能详解 › 素材管理 › 素材列表");
    }

    @Test
    void keepsOnlyTheDeepestThreeLevels() {
        String text = "# A\n## B\n### C\n#### D\n正文内容足够长。";
        List<DocumentChunker.ParentChildPiece> pieces = chunker.splitParentChild(text);
        // 切片以几行标题开头时算到最后一个标题；只保留最深的三级
        assertThat(chunker.contextTitles(text, pieces, "手册")).containsExactly("手册 › B › C › D");
    }

    @Test
    void ignoresHashLinesInsideCodeFences() {
        String text = "# 安装\n```\n# 这是注释\n```\n" + "安装说明。".repeat(120);
        List<DocumentChunker.ParentChildPiece> pieces = chunker.splitParentChild(text);
        List<String> titles = chunker.contextTitles(text, pieces, "指南");
        assertThat(titles).allMatch(t -> t.equals("指南 › 安装"));
    }

    @Test
    void noHeadingsGivesDocumentNameOnly() {
        String text = "普通文本。".repeat(200);
        List<DocumentChunker.ParentChildPiece> pieces = chunker.splitParentChild(text);
        assertThat(chunker.contextTitles(text, pieces, "说明")).allMatch("说明"::equals);
        assertThat(chunker.contextTitles(text, pieces, null)).allMatch(t -> t == null);
    }

    @Test
    void helpers() {
        assertThat(DocumentChunker.documentTitle("docs/ShiMeta手册.pdf")).isEqualTo("ShiMeta手册");
        assertThat(DocumentChunker.documentTitle("a\\b\\guide.md")).isEqualTo("guide");
        assertThat(DocumentChunker.indexText("手册 › 安装", "正文")).isEqualTo("手册 › 安装\n正文");
        assertThat(DocumentChunker.indexText(null, "正文")).isEqualTo("正文");
    }
}
