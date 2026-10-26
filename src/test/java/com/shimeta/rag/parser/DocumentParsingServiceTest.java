package com.shimeta.rag.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParsingServiceTest {

    private DocumentParsingService service;

    @BeforeEach
    void setUp() {
        service = new DocumentParsingService(null);
    }

    @Test
    @DisplayName("测试 CSV 解析并自动携带表头")
    void testCsvHeaderPropagation() {
        String csvContent = "姓名,年龄,部门,职位\n张三,28,技术部,Java研发工程师\n李四,32,产品部,高级产品经理";
        byte[] bytes = csvContent.getBytes(StandardCharsets.UTF_8);

        DocumentParsingService.ParseResult result = service.parse(bytes, "staff.csv");

        assertThat(result.format()).isEqualTo("CSV");
        assertThat(result.parserType()).isEqualTo("CSV_HEADER_ATTACH_PARSER");
        assertThat(result.isScanned()).isFalse();
        assertThat(result.text()).contains("【表格表头】姓名 | 年龄 | 部门 | 职位");
        assertThat(result.text()).contains("[数据行 #1] 姓名: 张三 | 年龄: 28 | 部门: 技术部 | 职位: Java研发工程师");
        assertThat(result.text()).contains("[数据行 #2] 姓名: 李四 | 年龄: 32 | 部门: 产品部 | 职位: 高级产品经理");
        assertThat(result.metadata().get("rowCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("测试 TSV 解析")
    void testTsvParsing() {
        String tsvContent = "code\tname\tprice\n001\tApple\t5.5\n002\tBanana\t3.2";
        byte[] bytes = tsvContent.getBytes(StandardCharsets.UTF_8);

        DocumentParsingService.ParseResult result = service.parse(bytes, "fruits.tsv");

        assertThat(result.format()).isEqualTo("TSV");
        assertThat(result.text()).contains("code: 001 | name: Apple | price: 5.5");
    }

    @Test
    @DisplayName("测试 DOCX OOXML 解构解析")
    void testDocxParsing() throws IOException {
        // 创建模拟 OOXML docx ZIP 包
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("word/document.xml");
            zos.putNextEntry(entry);
            String xml = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                        <w:body>
                            <w:p><w:r><w:t>关于2026年度业务架构升级规范通知</w:t></w:r></w:p>
                            <w:p><w:r><w:t>第一章：全面启用 Spring AI 自研 RAG 双引擎架构体系</w:t></w:r></w:p>
                        </w:body>
                    </w:document>
                    """;
            zos.write(xml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        byte[] docxBytes = baos.toByteArray();
        DocumentParsingService.ParseResult result = service.parse(docxBytes, "doc_spec.docx");

        assertThat(result.format()).isEqualTo("DOCX");
        assertThat(result.parserType()).isEqualTo("DOCX_OOXML_PARSER");
        assertThat(result.text()).contains("关于2026年度业务架构升级规范通知");
        assertThat(result.text()).contains("全面启用 Spring AI 自研 RAG 双引擎架构体系");
        assertThat(result.metadata().get("paragraphCount")).isEqualTo(2);
    }

    /** 生成真实 PDF：每页若干行文字（PDFBox 默认压缩内容流，与 Word/WPS 导出的 PDF 一致）；lines 为空则生成空白页 */
    private static byte[] pdf(int pages, List<String> lines, String userPassword) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (lines.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.newLineAtOffset(50, 720);
                    for (String line : lines) {
                        cs.showText(line + " (page " + i + ")");
                        cs.newLineAtOffset(0, -16);
                    }
                    cs.endText();
                }
            }
            if (userPassword != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", userPassword, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("PDF：压缩内容流中的文字能正常提取，按真实页数统计")
    void testPdfTextLayerExtraction() throws IOException {
        byte[] bytes = pdf(3, List.of("Spring AI RAG Knowledge Base Architecture",
                "Second line of high quality context for retrieval"), null);
        // 内容流已压缩：原始字节里看不到明文（旧实现正是因此把这类 PDF 误判为扫描件）
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("Knowledge Base Architecture");

        DocumentParsingService.ParseResult result = service.parse(bytes, "spec.pdf");

        assertThat(result.format()).isEqualTo("PDF");
        assertThat(result.isScanned()).isFalse();
        assertThat(result.parserType()).isEqualTo("PDF_TEXT_PARSER");
        assertThat(result.pageCount()).isEqualTo(3);
        assertThat(result.text()).contains("Spring AI RAG Knowledge Base Architecture (page 1)")
                .contains("Second line of high quality context for retrieval (page 3)");
        assertThat(result.metadata()).containsEntry("pagesWithText", 3);
    }

    @Test
    @DisplayName("PDF：没有文字层时判定为扫描件，未配置 OCR 时生成提示切片")
    void testPdfWithoutTextLayerIsScanned() throws IOException {
        DocumentParsingService.ParseResult result = service.parse(pdf(2, List.of(), null), "scanned_doc.pdf");

        assertThat(result.isScanned()).isTrue();
        assertThat(result.parserType()).isEqualTo("PDF_SCAN_PENDING_OCR");
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.text()).contains("扫描件").contains("共 2 页");
    }

    @Test
    @DisplayName("PDF：加密或损坏的文件给出说明，而不是把二进制内容当文本入库")
    void testUnreadablePdf() throws IOException {
        DocumentParsingService.ParseResult encrypted = service.parse(pdf(1, List.of("secret"), "pwd"), "locked.pdf");
        assertThat(encrypted.parserType()).isEqualTo("PDF_UNREADABLE");
        assertThat(encrypted.text()).contains("打开密码");

        byte[] broken = "%PDF-1.4\nnot really a pdf".getBytes(StandardCharsets.ISO_8859_1);
        DocumentParsingService.ParseResult corrupted = service.parse(broken, "broken.pdf");
        assertThat(corrupted.parserType()).isEqualTo("PDF_UNREADABLE");
        assertThat(corrupted.text()).startsWith("【PDF 无法读取】");
    }

    @Test
    @DisplayName("PDF：目录引导线与版面空白被整理")
    void testTidyPdfText() {
        String tidied = DocumentParsingService.tidyPdfText(
                "1 系统概述 ........................ 4   \n2.1   系统登录 ·········· 5\n\n\n\n下一段");
        assertThat(tidied).isEqualTo("1 系统概述 … 4\n2.1 系统登录 … 5\n\n下一段");
    }

    @Test
    @DisplayName("测试普通文本解析与 GBK 降级探测")
    void testPlainTextParsing() {
        String text = "这是 UTF-8 编码的知识库普通文本";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        DocumentParsingService.ParseResult result = service.parse(bytes, "info.txt");

        assertThat(result.format()).isEqualTo("TXT");
        assertThat(result.parserType()).isEqualTo("DIRECT_TEXT_PARSER");
        assertThat(result.text()).isEqualTo(text);
        assertThat(result.charCount()).isEqualTo(text.length());
    }
}
