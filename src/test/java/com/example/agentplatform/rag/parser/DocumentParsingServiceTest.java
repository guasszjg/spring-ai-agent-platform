package com.example.agentplatform.rag.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @Test
    @DisplayName("测试 PDF 流式提取与扫描件判定")
    void testPdfStreamAndScanDetection() {
        // 模拟无字符流的扫描件 PDF
        String scannedPdf = "%PDF-1.4\n1 0 obj\n<< /Type /Page >>\nendobj\nstream\nendstream\n%%EOF";
        byte[] scannedBytes = scannedPdf.getBytes(StandardCharsets.ISO_8859_1);

        DocumentParsingService.ParseResult scanResult = service.parse(scannedBytes, "scanned_doc.pdf");
        assertThat(scanResult.format()).isEqualTo("PDF");
        assertThat(scanResult.isScanned()).isTrue();
        assertThat(scanResult.parserType()).contains("PDF_SCAN");

        // 模拟含文字层的数据 PDF
        String textPdf = "%PDF-1.4\n/Page\nstream\n(Spring AI RAG Knowledge Base Architecture) Tj\n(Second line of high quality context) Tj\nendstream\n%%EOF";
        byte[] textBytes = textPdf.getBytes(StandardCharsets.ISO_8859_1);

        DocumentParsingService.ParseResult textResult = service.parse(textBytes, "spec.pdf");
        assertThat(textResult.format()).isEqualTo("PDF");
        assertThat(textResult.text()).contains("Spring AI RAG Knowledge Base Architecture");
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
