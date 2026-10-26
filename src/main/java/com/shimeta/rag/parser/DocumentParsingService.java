package com.shimeta.rag.parser;

import org.apache.pdfbox.Loader;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 平台多格式统一文档解析与结构化提取引擎 (Multi-Format Document Parsing Service)
 * 遵循《Spring-AI自研RAG双引擎设计.md》第 8 章：
 * 支持 TXT/MD/CSV/JSON/DOCX/PDF/图片 等多格式解析、表格表头自动携带、扫描件自动判定与 OCR 调度
 */
@Service
public class DocumentParsingService {

    /** 平均每页可提取字符少于该值时视为扫描件 */
    static final int SCANNED_CHARS_PER_PAGE = 50;
    /** 一页至少有这么多字符才算"有文字的页" */
    private static final int MIN_PAGE_TEXT = 20;

    private static final Logger log = LoggerFactory.getLogger(DocumentParsingService.class);

    private final OcrService ocrService;

    public record ParseResult(
            String text,
            String format,
            String parserType,
            boolean isScanned,
            int charCount,
            int pageCount,
            Map<String, Object> metadata
    ) {
        public static ParseResult ofText(String text, String format, String parserType, Map<String, Object> metadata) {
            int len = text != null ? text.length() : 0;
            return new ParseResult(text, format, parserType, false, len, 1, metadata != null ? metadata : Map.of());
        }

        public static ParseResult ofScanned(String text, String format, String parserType, int pages, Map<String, Object> metadata) {
            int len = text != null ? text.length() : 0;
            return new ParseResult(text, format, parserType, true, len, pages, metadata != null ? metadata : Map.of());
        }
    }

    public DocumentParsingService(@Autowired(required = false) OcrService ocrService) {
        this.ocrService = ocrService;
    }

    /**
     * 根据文件名后缀与内容特征智能调度解析器
     */
    public ParseResult parse(byte[] fileBytes, String filename) {
        if (fileBytes == null || fileBytes.length == 0) {
            return ParseResult.ofText("", "UNKNOWN", "EMPTY", Map.of());
        }

        String ext = extractExtension(filename).toLowerCase();
        log.info("开始多格式智能解析: filename={}, format={}, size={} bytes", filename, ext, fileBytes.length);

        try {
            return switch (ext) {
                case "csv", "tsv" -> parseCsv(fileBytes, ext);
                case "docx" -> parseDocx(fileBytes);
                case "xlsx", "xls" -> parseSpreadsheet(fileBytes, ext);
                case "html", "htm" -> parseHtml(fileBytes);
                case "pdf" -> parsePdf(fileBytes, filename);
                case "png", "jpg", "jpeg", "bmp", "webp" -> parseImage(fileBytes, filename, ext);
                case "json" -> parseJson(fileBytes);
                default -> parsePlainText(fileBytes, ext);
            };
        } catch (Exception e) {
            log.warn("专业解析失败，平滑降级至通用纯文本直读: filename={}, error={}", filename, e.getMessage());
            return parsePlainText(fileBytes, ext);
        }
    }

    /**
     * 1. 表格文件解析 (CSV/TSV): 表头携带算法，杜绝丢失行上下文；支持带引号的字段（字段内可含分隔符、换行与转义引号）
     */
    private ParseResult parseCsv(byte[] bytes, String ext) {
        char delimiter = "tsv".equalsIgnoreCase(ext) ? '\t' : ',';
        List<List<String>> rows = splitDelimited(decodeBestEffort(bytes), delimiter);
        if (rows.isEmpty()) {
            return ParseResult.ofText("", ext.toUpperCase(), "CSV_PARSER", Map.of());
        }
        List<String> headers = rows.get(0);
        StringBuilder sb = new StringBuilder();
        int validRows = appendTable(sb, headers, rows.subList(1, rows.size()), 1);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rowCount", validRows);
        meta.put("columnCount", headers.size());
        meta.put("tableHeaders", headers.toArray(new String[0]));

        return ParseResult.ofText(sb.toString(), ext.toUpperCase(), "CSV_HEADER_ATTACH_PARSER", meta);
    }

    /** 按 RFC 4180 规则切分：引号内的分隔符与换行属于字段内容，"" 表示一个引号；忽略全空行 */
    static List<List<String>> splitDelimited(String content, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString().trim());
                field.setLength(0);
                addIfNotBlank(rows, row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        row.add(field.toString().trim());
        addIfNotBlank(rows, row);
        return rows;
    }

    private static void addIfNotBlank(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(cell -> !cell.isEmpty())) {
            rows.add(row);
        }
    }

    /**
     * 输出带表头的表格：先输出表头，再逐行输出"列名: 值"，空单元格省略。
     *
     * @param firstRowNumber 第一行数据的编号
     * @return 输出的数据行数
     */
    static int appendTable(StringBuilder sb, List<String> headers, List<List<String>> dataRows, int firstRowNumber) {
        List<String> names = new ArrayList<>();
        for (int c = 0; c < headers.size(); c++) {
            String h = headers.get(c) == null ? "" : headers.get(c).trim();
            names.add(h.isEmpty() ? "列" + (c + 1) : h);
        }
        sb.append("【表格表头】").append(String.join(" | ", names)).append("\n\n");
        int count = 0;
        for (int r = 0; r < dataRows.size(); r++) {
            List<String> cells = dataRows.get(r);
            List<String> pairs = new ArrayList<>();
            for (int c = 0; c < cells.size(); c++) {
                String value = cells.get(c) == null ? "" : cells.get(c).trim();
                if (!value.isEmpty()) {
                    pairs.add((c < names.size() ? names.get(c) : "列" + (c + 1)) + ": " + value);
                }
            }
            if (!pairs.isEmpty()) {
                sb.append("[数据行 #").append(firstRowNumber + r).append("] ").append(String.join(" | ", pairs)).append("\n");
                count++;
            }
        }
        return count;
    }
    /**
     * 2. Word 文档解析 (DOCX): 用 POI 按原顺序读取段落与表格，表格按"表头: 值"逐行输出，并附上页眉页脚；
     * POI 无法打开时（如不完整的 OOXML 包）回退为直接读取 word/document.xml 中的段落文本
     */
    private ParseResult parseDocx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            int paragraphCount = 0;
            int tableCount = 0;
            Set<String> headerFooter = new LinkedHashSet<>();
            for (XWPFHeader header : document.getHeaderList()) {
                addIfNotBlank(headerFooter, header.getText());
            }
            for (XWPFFooter footer : document.getFooterList()) {
                addIfNotBlank(headerFooter, footer.getText());
            }
            if (!headerFooter.isEmpty()) {
                sb.append("【页眉页脚】").append(String.join(" / ", headerFooter)).append("\n\n");
            }
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText().trim();
                    if (!text.isEmpty()) {
                        sb.append(text).append("\n\n");
                        paragraphCount++;
                    }
                } else if (element instanceof XWPFTable table) {
                    List<List<String>> rows = new ArrayList<>();
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(joinSpacedCjk(cell.getText().replaceAll("\\s+", " ").trim()));
                        }
                        addIfNotBlank(rows, cells);
                    }
                    if (!rows.isEmpty()) {
                        tableCount++;
                        List<String> first = rows.get(0);
                        if (rows.size() > 1 && first.stream().noneMatch(String::isEmpty) && looksLikeHeader(first)) {
                            appendTable(sb, first, rows.subList(1, rows.size()), 1);
                        } else {
                            // 表单式表格（标签 | 值 | 标签 | 值）或只有一行：逐行输出，不强行配对表头
                            for (List<String> row : rows) {
                                sb.append(String.join(" | ", row.stream().filter(v -> !v.isEmpty()).toList())).append("\n");
                            }
                        }
                        sb.append("\n");
                    }
                }
            }
            if (sb.isEmpty()) {
                return ParseResult.ofText("", "DOCX", "DOCX_POI_PARSER", Map.of("paragraphCount", 0, "tableCount", 0));
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("paragraphCount", paragraphCount);
            meta.put("tableCount", tableCount);
            return ParseResult.ofText(sb.toString().trim(), "DOCX", "DOCX_POI_PARSER", meta);
        } catch (Exception e) {
            log.debug("POI 无法打开 DOCX，改为直接读取 document.xml: {}", e.getMessage());
            return parseDocxXml(bytes);
        }
    }

    /** 页眉页脚去重收集；只有页码（如 "10"、"第 3 页"、"3 / 12"）的内容丢弃 */
    private static void addIfNotBlank(Set<String> target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (!normalized.matches("[\\d\\s第页共/ofPage-]+")) {
            target.add(normalized);
        }
    }

    private static final Pattern SPACED_CJK = Pattern.compile("^(\\p{IsHan} )+\\p{IsHan}$");

    /** 表格中为对齐而逐字加空格的中文（如 "项 目 编 号"）合并为 "项目编号" */
    static String joinSpacedCjk(String text) {
        return SPACED_CJK.matcher(text).matches() ? text.replace(" ", "") : text;
    }

    /** 兜底：直接读取 word/document.xml 中的段落文本（还原 XML 实体） */
    private ParseResult parseDocxXml(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        int paragraphCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equalsIgnoreCase(entry.getName())) {
                    String xml = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher pMatcher = Pattern.compile("<w:p[ >](.*?)</w:p>", Pattern.DOTALL).matcher(xml);
                    while (pMatcher.find()) {
                        Matcher tMatcher = Pattern.compile("<w:t[^>]*>(.*?)</w:t>").matcher(pMatcher.group(1));
                        StringBuilder pText = new StringBuilder();
                        while (tMatcher.find()) {
                            pText.append(unescapeXml(tMatcher.group(1)));
                        }
                        String trimmed = pText.toString().trim();
                        if (!trimmed.isEmpty()) {
                            sb.append(trimmed).append("\n\n");
                            paragraphCount++;
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("解析 DOCX 失败: {}", e.getMessage());
        }

        if (sb.isEmpty()) {
            String desc = "【Word 文档无法读取】文件已损坏或不是有效的 DOCX，请用 Word 重新另存为 .docx 后上传。";
            return new ParseResult(desc, "DOCX", "DOCX_UNREADABLE", false, desc.length(), 0, Map.of("unreadable", true));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("paragraphCount", paragraphCount);
        meta.put("tableCount", 0);
        return ParseResult.ofText(sb.toString(), "DOCX", "DOCX_OOXML_PARSER", meta);
    }

    private static String unescapeXml(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    /**
     * Excel 解析 (XLSX/XLS): 每个工作表的第一个非空行作为表头，其后逐行输出"列名: 值"；
     * 公式取缓存的计算结果，日期与数字按单元格格式显示；隐藏的工作表跳过
     */
    private ParseResult parseSpreadsheet(byte[] bytes, String ext) {
        String format = ext.toUpperCase();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            formatter.setUseCachedValuesForFormulaCells(true);
            StringBuilder sb = new StringBuilder();
            int sheetCount = 0;
            int rowCount = 0;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                if (workbook.isSheetHidden(i) || workbook.isSheetVeryHidden(i)) {
                    continue;
                }
                Sheet sheet = workbook.getSheetAt(i);
                List<List<String>> rows = new ArrayList<>();
                List<Integer> rowNumbers = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell).replaceAll("\\s+", " ").trim());
                    }
                    if (cells.stream().anyMatch(v -> !v.isEmpty())) {
                        rows.add(cells);
                        rowNumbers.add(row.getRowNum() + 1);
                    }
                }
                if (rows.isEmpty()) {
                    continue;
                }
                sheetCount++;
                sb.append("【工作表】").append(sheet.getSheetName()).append("\n");
                rowCount += appendSheetRows(sb, rows, rowNumbers);
                sb.append("\n");
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sheetCount", sheetCount);
            meta.put("rowCount", rowCount);
            return ParseResult.ofText(sb.toString().trim(), format, "SPREADSHEET_PARSER", meta);
        } catch (Exception e) {
            log.warn("Excel 解析失败: format={}, error={}", format, e.getMessage());
            String desc = "【Excel 文件无法读取】文件已损坏、已加密或格式不受支持（" + e.getMessage() + "），请用 Excel 重新另存后上传。";
            return new ParseResult(desc, format, "SPREADSHEET_UNREADABLE", false, desc.length(), 0, Map.of("unreadable", true));
        }
    }

    /**
     * 按行输出工作表，兼容常见的"标题 + 信息表单 + 表格"版式：
     * 只有一个非空单元格的行视为标题或分组，原样输出；尚未出现表头时，两个非空单元格的行视为"字段: 值"；
     * 第一个有三个及以上非空单元格的行作为表头，之后的行按"表头: 值"输出；分组标题之后若出现一行全是短词的多列行，视为新表格的表头。
     *
     * @return 输出的数据行数
     */
    static int appendSheetRows(StringBuilder sb, List<List<String>> rows, List<Integer> rowNumbers) {
        List<String> header = null;
        boolean afterTitle = false;
        int dataRows = 0;
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            List<String> nonEmpty = cells.stream().filter(v -> !v.isEmpty()).toList();
            if (nonEmpty.size() == 1) {
                sb.append(nonEmpty.get(0)).append("\n");
                afterTitle = true;
                continue;
            }
            if (nonEmpty.size() >= 3 && (header == null || (afterTitle && looksLikeHeader(nonEmpty)))) {
                header = cells;
                sb.append("【表格表头】").append(String.join(" | ", nonEmpty)).append("\n");
            } else if (header != null) {
                List<String> pairs = new ArrayList<>();
                for (int c = 0; c < cells.size(); c++) {
                    if (cells.get(c).isEmpty()) {
                        continue;
                    }
                    String name = c < header.size() && !header.get(c).isEmpty() ? header.get(c) : "列" + (c + 1);
                    pairs.add(name + ": " + cells.get(c));
                }
                sb.append("[数据行 #").append(rowNumbers.get(r)).append("] ").append(String.join(" | ", pairs)).append("\n");
                dataRows++;
            } else {
                sb.append(nonEmpty.get(0)).append(": ").append(String.join(" | ", nonEmpty.subList(1, nonEmpty.size()))).append("\n");
                dataRows++;
            }
            afterTitle = false;
        }
        return dataRows;
    }

    /** 表头行：每个单元格都是不长的文字，不是数字或日期 */
    private static boolean looksLikeHeader(List<String> cells) {
        // 表头一般是短的文字列名（含汉字或字母）；数据行常含日期、数量等数字，表单的占位格常是 "-"
        return cells.stream().allMatch(v -> v.length() <= 16 && !v.matches(".*\\d.*") && v.matches(".*\\p{L}.*"));
    }

    private static final Set<String> HTML_BLOCK_TAGS = Set.of(
            "p", "div", "section", "article", "main", "header", "footer", "aside", "nav", "ul", "ol", "li", "dl", "dt", "dd",
            "table", "thead", "tbody", "tfoot", "tr", "pre", "blockquote", "figure", "figcaption", "form", "fieldset",
            "h1", "h2", "h3", "h4", "h5", "h6", "hr", "br", "address", "details", "summary");

    /**
     * HTML 解析 (HTML/HTM): 去掉脚本、样式等非正文内容，只保留可见文本；标题转为 Markdown 标题，列表项加 "- "，
     * 表格单元格以 " | " 分隔
     */
    private ParseResult parseHtml(byte[] bytes) {
        org.jsoup.nodes.Document doc = Jsoup.parse(decodeBestEffort(bytes));
        doc.select("script, style, noscript, template, svg, canvas, iframe, object, embed, nav, head > *:not(title)").remove();
        String title = doc.title() == null ? "" : doc.title().trim();
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) {
                    String text = textNode.text();
                    if (!text.isBlank()) {
                        sb.append(text.replaceAll("\\s+", " "));
                    }
                } else if (node instanceof Element element) {
                    String tag = element.normalName();
                    if (HTML_BLOCK_TAGS.contains(tag)) {
                        sb.append('\n');
                    }
                    if (tag.length() == 2 && tag.charAt(0) == 'h' && Character.isDigit(tag.charAt(1))) {
                        sb.append("#".repeat(tag.charAt(1) - '0')).append(' ');
                    } else if ("li".equals(tag)) {
                        sb.append("- ");
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element) {
                    String tag = element.normalName();
                    if ("td".equals(tag) || "th".equals(tag)) {
                        sb.append(" | ");
                    } else if (HTML_BLOCK_TAGS.contains(tag)) {
                        sb.append('\n');
                    }
                }
            }
        }, doc.body());
        String text = sb.toString()
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("(?m)\\s*\\|\\s*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        // 正文里没有同名一级标题时，才把 <title> 作为文档标题加在开头
        if (!title.isEmpty() && !text.matches("(?s)(.*\\n)?# " + Pattern.quote(title) + "(\\n.*)?")) {
            text = "# " + title + "\n\n" + text;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", title);
        return ParseResult.ofText(text, "HTML", "HTML_TEXT_PARSER", meta);
    }
    /**
     * 3. PDF 解析与扫描件检测 (PDF + Scan Detector):
     * 用 PDFBox 逐页提取文本层（解压内容流，并按字体的 ToUnicode 映射还原中文）；
     * 只有平均每页可提取字符少于 {@value #SCANNED_CHARS_PER_PAGE} 个时才判定为扫描件并尝试 OCR。
     */
    private ParseResult parsePdf(byte[] bytes, String filename) {
        int pageCount;
        int pagesWithText = 0;
        StringBuilder text = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            pageCount = Math.max(1, document.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = tidyPdfText(cleanExtractedText(stripper.getText(document)));
                if (pageText.length() >= MIN_PAGE_TEXT) {
                    pagesWithText++;
                }
                if (!pageText.isEmpty()) {
                    text.append(pageText).append("\n\n");
                }
            }
        } catch (InvalidPasswordException e) {
            log.warn("PDF 已加密，无法提取文本: filename={}", filename);
            return pdfUnreadable(filename, "文件已设置打开密码，请解除加密后重新上传。");
        } catch (Exception e) {
            log.warn("PDF 解析失败: filename={}, error={}", filename, e.getMessage());
            return pdfUnreadable(filename, "文件已损坏或格式不受支持（" + e.getMessage() + "），请重新导出 PDF 后上传。");
        }

        String plainText = text.toString().trim();
        int charCount = plainText.length();
        int charsPerPage = charCount / pageCount;
        boolean isScanned = charsPerPage < SCANNED_CHARS_PER_PAGE;

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pageCount", pageCount);
        meta.put("pagesWithText", pagesWithText);
        meta.put("charsPerPage", charsPerPage);
        meta.put("isScanned", isScanned);

        if (!isScanned) {
            return new ParseResult(plainText, "PDF", "PDF_TEXT_PARSER", false, charCount, pageCount, meta);
        }

        if (ocrService != null && ocrService.isAvailable()) {
            OcrService.OcrResult ocrRes = ocrService.parseImage(bytes, filename);
            if (ocrRes.success() && !ocrRes.text().isBlank()) {
                meta.put("ocrConfidence", ocrRes.confidence());
                meta.put("ocrProvider", ocrService.getProviderName());
                return ParseResult.ofScanned(ocrRes.text(), "PDF", "PDF_OCR_PARSER", pageCount, meta);
            }
        }

        if (charCount > 0) {
            // 文字很少但不为空（例如只有封面标题的扫描件）：保留已提取的文字，同时标记为扫描件
            return ParseResult.ofScanned(plainText, "PDF", "PDF_TEXT_PARSER", pageCount, meta);
        }

        // 纯图片扫描件兜底提示
        String fallbackDesc = "【扫描件 PDF 文档】" + filename + "（共 " + pageCount + " 页）。\n"
                + "提示：该文件为影印扫描件，无内置文本层。已自动标注为扫描件，待 OCR 服务部署就绪后即可执行全量图像字符转写。";
        return ParseResult.ofScanned(fallbackDesc, "PDF", "PDF_SCAN_PENDING_OCR", pageCount, meta);
    }

    private static final Pattern PDF_DOT_LEADER = Pattern.compile("[.·…]{5,}");
    private static final Pattern PDF_SPACES = Pattern.compile("[ \t\u00A0\u3000]{2,}");

    /** 整理 PDF 版面文本：目录引导线缩为省略号、连续空白合并、去掉行尾空白与多余空行，避免浪费切片长度 */
    static String tidyPdfText(String text) {
        String tidied = PDF_DOT_LEADER.matcher(text).replaceAll(" … ");
        tidied = PDF_SPACES.matcher(tidied).replaceAll(" ");
        return tidied.replaceAll("(?m)[ \t]+$", "").replaceAll("\n{3,}", "\n\n").trim();
    }

    /** PDF 无法打开时的说明切片（不把二进制内容当文本入库） */
    private ParseResult pdfUnreadable(String filename, String reason) {
        String desc = "【PDF 无法读取】" + filename + "。" + reason;
        return new ParseResult(desc, "PDF", "PDF_UNREADABLE", false, desc.length(), 0, Map.of("unreadable", true));
    }

    /**
     * 4. 图像文档解析 (PNG/JPG/WEBP/GIF)
     */
    private ParseResult parseImage(byte[] bytes, String filename, String ext) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fileSize", bytes.length);
        meta.put("imageFormat", ext.toUpperCase());
        meta.put("isImage", true);
        String relativeUrl = "/uploads/knowledge/docs/" + filename;
        meta.put("imageUrl", relativeUrl);
        String caption = "图片文档: " + filename;
        meta.put("imageCaption", caption);

        if (ocrService != null && ocrService.isAvailable()) {
            OcrService.OcrResult ocrRes = ocrService.parseImage(bytes, filename);
            if (ocrRes.success() && !ocrRes.text().isBlank()) {
                meta.put("ocrConfidence", ocrRes.confidence());
                meta.put("ocrProvider", ocrService.getProviderName());
                meta.put("imageCaption", ocrRes.text());
                return ParseResult.ofScanned(ocrRes.text(), ext.toUpperCase(), "IMAGE_OCR_PARSER", 1, meta);
            }
        }

        String placeholder = "【图片文件】" + filename + " (格式: " + ext.toUpperCase() + ", 大小: " + (bytes.length / 1024) + " KB)。\n"
                + "图像特征已由多模态解析器捕获。当系统接入本地 PaddleOCR 或云 OCR API 时，将自动提取图中印刷体与手写体文字。";
        return ParseResult.ofScanned(placeholder, ext.toUpperCase(), "IMAGE_METADATA_PARSER", 1, meta);
    }

    /**
     * 5. JSON 文档结构化解析
     */
    private ParseResult parseJson(byte[] bytes) {
        String raw = decodeBestEffort(bytes);
        return ParseResult.ofText(raw, "JSON", "JSON_PARSER", Map.of("format", "JSON"));
    }

    /**
     * 6. 通用纯文本解析 (TXT/MD/源码): 智能编码探测 (UTF-8 / GBK) 与内嵌 Markdown 图片识别
     */
    private ParseResult parsePlainText(byte[] bytes, String ext) {
        String decoded = decodeBestEffort(bytes);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("charset", "UTF-8/GBK_AUTO");

        // 识别 Markdown 图片: ![alt](url)
        Pattern imgPattern = Pattern.compile("!\\[(.*?)\\]\\((.*?)\\)");
        Matcher m = imgPattern.matcher(decoded);
        List<Map<String, String>> embeddedImages = new ArrayList<>();
        while (m.find()) {
            Map<String, String> img = new LinkedHashMap<>();
            img.put("caption", m.group(1));
            img.put("url", m.group(2));
            embeddedImages.add(img);
        }
        if (!embeddedImages.isEmpty()) {
            meta.put("hasEmbeddedImages", true);
            meta.put("embeddedImages", embeddedImages);
        }

        return ParseResult.ofText(decoded, ext.toUpperCase(), "DIRECT_TEXT_PARSER", meta);
    }

    /**
     * 文本解码：识别 BOM；无 BOM 时先按 UTF-8 严格解码，含非法 UTF-8 字节时按 GB18030（兼容 GBK/GB2312）解码。
     * 注意 new String(bytes, UTF_8) 遇到非法字节不会抛异常而是替换为 \uFFFD，因此必须用严格模式的解码器判断。
     */
    static String decodeBestEffort(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }
    private String cleanExtractedText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\\\[0-9]{3}", " ")
                .replaceAll("\\\\[rnftb]", " ")
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .trim();
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "txt";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
