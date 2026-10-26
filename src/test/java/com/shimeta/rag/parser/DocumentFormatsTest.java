package com.shimeta.rag.parser;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 知识库上传支持的各格式解析：文本编码、CSV、Excel、Word、HTML */
class DocumentFormatsTest {

    private final DocumentParsingService service = new DocumentParsingService(null);

    // ==================== 文本编码 ====================

    @Test
    @DisplayName("GBK 编码的 TXT / CSV 不再乱码；UTF-8（含 BOM）照常读取")
    void detectsGbkAndUtf8() {
        Charset gbk = Charset.forName("GBK");
        assertThat(service.parse("开机黑屏怎么办？请检查电源适配器。".getBytes(gbk), "faq.txt").text())
                .isEqualTo("开机黑屏怎么办？请检查电源适配器。");

        String csv = service.parse("问题,答案\n怎么开机,长按电源键 3 秒\n".getBytes(gbk), "faq.csv").text();
        assertThat(csv).contains("[数据行 #1] 问题: 怎么开机 | 答案: 长按电源键 3 秒").doesNotContain("�");

        byte[] bom = "﻿终端激活".getBytes(StandardCharsets.UTF_8);
        assertThat(service.parse(bom, "a.md").text()).isEqualTo("终端激活");
        assertThat(service.parse("终端激活".getBytes(StandardCharsets.UTF_8), "a.md").text()).isEqualTo("终端激活");
    }

    @Test
    @DisplayName("CSV 带引号的字段：字段内的逗号、换行和转义引号不会被拆开")
    void csvQuotedFields() {
        String csv = "型号,说明\n\"55寸, 立式\",\"支持\"\"触摸\"\"\n和语音\"\n\n43寸,壁挂\n";
        String text = service.parse(csv.getBytes(StandardCharsets.UTF_8), "products.csv").text();

        assertThat(text).contains("[数据行 #1] 型号: 55寸, 立式 | 说明: 支持\"触摸\"\n和语音")
                .contains("[数据行 #2] 型号: 43寸 | 说明: 壁挂");
    }

    // ==================== Excel ====================

    private static byte[] save(Workbook wb) throws IOException {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void row(Sheet sheet, int index, Object... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                continue;
            }
            Cell cell = row.createCell(i);
            if (values[i] instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else {
                cell.setCellValue(values[i].toString());
            }
        }
    }

    @Test
    @DisplayName("XLSX：表头配对，公式取计算结果，日期按格式显示，隐藏工作表跳过")
    void xlsxTable() throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("报价单");
        row(sheet, 0, "型号", "单价", "数量", "小计", "交付日期");
        row(sheet, 2, "55寸广告机", 3999.5, 10);
        sheet.getRow(2).createCell(3).setCellFormula("B3*C3");
        CellStyle dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        Cell date = sheet.getRow(2).createCell(4);
        date.setCellValue(LocalDate.of(2026, 11, 1));
        date.setCellStyle(dateStyle);
        wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
        row(wb.createSheet("内部成本"), 0, "机密成本 1234");
        wb.setSheetHidden(1, true);

        DocumentParsingService.ParseResult result = service.parse(save(wb), "quote.xlsx");

        assertThat(result.format()).isEqualTo("XLSX");
        assertThat(result.parserType()).isEqualTo("SPREADSHEET_PARSER");
        assertThat(result.text()).contains("【工作表】报价单")
                .contains("【表格表头】型号 | 单价 | 数量 | 小计 | 交付日期")
                .contains("[数据行 #3] 型号: 55寸广告机 | 单价: 3999.5 | 数量: 10 | 小计: 39995 | 交付日期: 2026-11-01")
                .doesNotContain("机密成本");
    }

    @Test
    @DisplayName("Excel 常见版式：大标题 + 信息表单 + 带分组行的表格")
    void spreadsheetTitleFormAndTable() throws IOException {
        HSSFWorkbook wb = new HSSFWorkbook();
        Sheet sheet = wb.createSheet("规划");
        row(sheet, 0, "智能体广告机项目规划（第二阶段）");
        row(sheet, 1, "项目编号", "2026-007");
        row(sheet, 2, "项目经理", "李建");
        row(sheet, 3, "过程", "概述", "负责人员", "进度计划");
        row(sheet, 4, "里程碑--项目启动");
        row(sheet, 5, "项目启动", "确认范围与分工", "李建", "05-19~05-21");

        String text = service.parse(save(wb), "plan.xls").text();

        assertThat(text).contains("智能体广告机项目规划（第二阶段）\n项目编号: 2026-007\n项目经理: 李建\n")
                .contains("【表格表头】过程 | 概述 | 负责人员 | 进度计划\n里程碑--项目启动\n")
                .contains("[数据行 #6] 过程: 项目启动 | 概述: 确认范围与分工 | 负责人员: 李建 | 进度计划: 05-19~05-21")
                .doesNotContain("智能体广告机项目规划（第二阶段）: ");
    }

    @Test
    @DisplayName("损坏的 Excel 生成说明切片，而不是把二进制内容当文本入库")
    void brokenSpreadsheet() {
        DocumentParsingService.ParseResult result = service.parse("PK\u0003\u0004 not a workbook".getBytes(StandardCharsets.ISO_8859_1), "x.xlsx");
        assertThat(result.parserType()).isEqualTo("SPREADSHEET_UNREADABLE");
        assertThat(result.text()).startsWith("【Excel 文件无法读取】");
    }

    // ==================== Word ====================

    @Test
    @DisplayName("DOCX：按原顺序读段落与表格，规整表格按表头配对，表单式表格逐行输出，页码页脚丢弃")
    void docxParagraphsAndTables() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("视美泰 立项书");
        doc.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("10");
        doc.createParagraph().createRun().setText("商用AI智能体广告机立项书 & 附件");
        XWPFTable form = doc.createTable(2, 2);
        form.getRow(0).getCell(0).setText("项 目 编 号");
        form.getRow(0).getCell(1).setText("-");
        form.getRow(1).getCell(0).setText("分 管 副 总 裁");
        form.getRow(1).getCell(1).setText("常务副总裁");
        doc.createParagraph().createRun().setText("进度安排");
        XWPFTable table = doc.createTable(3, 2);
        table.getRow(0).getCell(0).setText("状态");
        table.getRow(0).getCell(1).setText("预计时间");
        table.getRow(1).getCell(0).setText("软件开发");
        table.getRow(1).getCell(1).setText("5月30日前完成");
        table.getRow(2).getCell(0).setText("多模态融合");
        table.getRow(2).getCell(1).setText("6月30日前完成");

        DocumentParsingService.ParseResult result;
        try (doc; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.write(out);
            result = service.parse(out.toByteArray(), "lixiang.docx");
        }

        String text = result.text();
        assertThat(result.parserType()).isEqualTo("DOCX_POI_PARSER");
        assertThat(result.metadata()).containsEntry("tableCount", 2);
        assertThat(text).startsWith("【页眉页脚】视美泰 立项书\n")
                .contains("商用AI智能体广告机立项书 & 附件")
                .contains("项目编号 | -\n分管副总裁 | 常务副总裁")
                .contains("【表格表头】状态 | 预计时间")
                .contains("[数据行 #1] 状态: 软件开发 | 预计时间: 5月30日前完成")
                .doesNotContain("10\n");
        assertThat(text.indexOf("商用AI")).isLessThan(text.indexOf("项目编号"));
        assertThat(text.indexOf("进度安排")).isLessThan(text.indexOf("【表格表头】状态"));
    }

    @Test
    @DisplayName("逐字加空格的中文合并，正常的中英文混排不受影响")
    void joinSpacedCjk() {
        assertThat(DocumentParsingService.joinSpacedCjk("项 目 编 号")).isEqualTo("项目编号");
        assertThat(DocumentParsingService.joinSpacedCjk("部 门 / 中 心")).isEqualTo("部 门 / 中 心");
        assertThat(DocumentParsingService.joinSpacedCjk("Android App 开发")).isEqualTo("Android App 开发");
    }

    // ==================== HTML ====================

    @Test
    @DisplayName("HTML：去掉脚本与样式，保留标题层级、列表与表格文字")
    void htmlVisibleText() {
        String html = """
                <!doctype html><html><head><meta charset="utf-8"><title>广告机帮助</title>
                <style>.x{color:red}</style><script>var secret = 1;</script></head>
                <body><nav>首页</nav><h1>广告机帮助</h1><h2>开机</h2><p>长按电源键   3 秒。</p>
                <ul><li>检查电源</li><li>检查网络</li></ul>
                <table><tr><th>型号</th><th>尺寸</th></tr><tr><td>A1</td><td>55寸</td></tr></table>
                <noscript>请开启 JS</noscript></body></html>
                """;
        DocumentParsingService.ParseResult result = service.parse(html.getBytes(StandardCharsets.UTF_8), "help.html");

        assertThat(result.parserType()).isEqualTo("HTML_TEXT_PARSER");
        assertThat(result.text()).contains("# 广告机帮助")
                .contains("## 开机")
                .contains("长按电源键 3 秒。")
                .contains("- 检查电源")
                .contains("型号 | 尺寸")
                .contains("A1 | 55寸")
                .doesNotContain("secret").doesNotContain("color:red").doesNotContain("<").doesNotContain("请开启 JS");
        assertThat(result.text().indexOf("# 广告机帮助")).isEqualTo(result.text().lastIndexOf("# 广告机帮助"));
    }

    @Test
    @DisplayName("GBK 编码的 HTML 同样能正确识别")
    void gbkHtml() {
        byte[] html = "<html><body><p>终端激活说明</p></body></html>".getBytes(Charset.forName("GBK"));
        assertThat(service.parse(html, "a.htm").text()).isEqualTo("终端激活说明");
    }
}
