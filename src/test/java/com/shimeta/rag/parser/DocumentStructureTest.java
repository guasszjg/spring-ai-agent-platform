package com.shimeta.rag.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStructureTest {

    @Test
    void marksNumberedHeadingsThatFollowOn() {
        String text = String.join("\n",
                "ShiMeta 使用手册",
                "1 系统概述",
                "1.1 概述",
                "正文内容。",
                "2 系统功能详解",
                "2.1 系统登录",
                "2.1.1 系统登录/退出",
                "1） 打开浏览器",
                "2.2 素材管理",
                "2.2.1 素材列表",
                "2.2.1.1 素材上传",
                "3 个设备同时在线时，需要注意带宽",
                "2.2.1.2 素材删除",
                "3 服务端安装说明");
        String marked = DocumentStructure.markNumberedHeadings(text);
        assertThat(marked).contains("# 1 系统概述\n## 1.1 概述\n正文内容。\n# 2 系统功能详解\n## 2.1 系统登录\n### 2.1.1 系统登录/退出\n1） 打开浏览器");
        assertThat(marked).contains("#### 2.2.1.1 素材上传").contains("#### 2.2.1.2 素材删除").contains("\n# 3 服务端安装说明");
        // 正文里的编号句子（以标点结尾，或编号接不上）不当成标题
        assertThat(marked).contains("\n3 个设备同时在线时，需要注意带宽\n");
    }

    @Test
    void tableOfContentsDecidesWhichNumbersAreHeadings() {
        String text = String.join("\n",
                "1 系统概述 … 4",
                "1.1 概述 … 4",
                "2 系统功能详解 … 5",
                "2.1 系统登录 … 5",
                "2.2 素材管理 … 6",
                "1 系统概述",
                "1.1 概述",
                "2 系统功能详解",
                "2.1 系统登录",
                "2.1.1 登录步骤",
                "2.2 台设备同时登录",
                "2.2 素材管理");
        String result = DocumentStructure.structure(text);
        assertThat(result).doesNotContain(" … ");
        // 目录覆盖两级：一、二级只认目录里的编号；目录没列到的三级按顺序判断
        assertThat(result).contains("# 1 系统概述\n## 1.1 概述\n# 2 系统功能详解\n## 2.1 系统登录\n### 2.1.1 登录步骤");
        assertThat(result).contains("\n## 2.2 素材管理");
        assertThat(DocumentStructure.tableOfContentsNumbers(text)).containsExactly("1", "1.1", "2", "2.1", "2.2");
    }

    @Test
    void rejectsNumbersThatDoNotFollowOn() {
        assertThat(DocumentStructure.followsOn(List.of(), List.of(1))).isTrue();
        assertThat(DocumentStructure.followsOn(List.of(), List.of(5))).isFalse();
        assertThat(DocumentStructure.followsOn(List.of(), List.of(1, 1))).isFalse();
        assertThat(DocumentStructure.followsOn(List.of(2, 2), List.of(2, 2, 1))).isTrue();
        assertThat(DocumentStructure.followsOn(List.of(2, 2), List.of(2, 3, 1))).isFalse();
        assertThat(DocumentStructure.followsOn(List.of(2, 2, 1, 1), List.of(2, 3))).isTrue();
        assertThat(DocumentStructure.followsOn(List.of(2, 2, 1, 1), List.of(3))).isTrue();
        assertThat(DocumentStructure.followsOn(List.of(2, 2), List.of(2, 1))).isFalse();
        assertThat(DocumentStructure.followsOn(List.of(2, 2), List.of(2, 9))).isFalse();
    }

    @Test
    void leavesMarkdownAndTextWithoutHeadingsAlone() {
        String md = "# 标题\n1 系统概述\n2 功能";
        assertThat(DocumentStructure.markNumberedHeadings(md)).isEqualTo(md);
        String plain = "1 只有一行像标题\n正文";
        assertThat(DocumentStructure.markNumberedHeadings(plain)).isEqualTo(plain);
    }

    @Test
    void removesTableOfContentsOnlyWhenThereAreEnoughLines() {
        String toc = String.join("\n",
                "使用手册",
                "1 系统概述 … 4",
                "1.1 概述 … 4",
                "2 系统功能详解 … 4",
                "2.1 系统登录 … 4",
                "4.4.2 U 盘导入双屏节目使用说明 … 错误!未定义书签。",
                "2.2 素材管理 … 6",
                "1 系统概述",
                "正文");
        String cleaned = DocumentStructure.removeTableOfContents(toc);
        assertThat(cleaned).isEqualTo("使用手册\n1 系统概述\n正文");

        String few = "价格 … 4\n正文";
        assertThat(DocumentStructure.removeTableOfContents(few)).isEqualTo(few);
    }

    @Test
    void stripsRepeatedPageHeadersAndFooters() {
        String[] bodies = {"封面\n标牌\n系统\n手册\n版本", "1 系统概述\n登录\n退出\n设备\n连接",
                "素材\n上传\n删除\n发布\n查询", "节目\n新建\n预览\n保存\n发布"};
        List<String> pages = new java.util.ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            pages.add("ShiMeta 文档 Page " + (i + 1) + " of 4\n" + bodies[i] + "\n第 " + (i + 1) + " 页");
        }
        List<String> cleaned = DocumentStructure.stripRepeatedPageLines(pages);
        assertThat(cleaned).containsExactly(bodies);
    }

    @Test
    void keepsPagesWhenTooFewToJudge() {
        List<String> pages = List.of("页眉\n正文一", "页眉\n正文二");
        assertThat(DocumentStructure.stripRepeatedPageLines(pages)).isEqualTo(pages);
    }
}
