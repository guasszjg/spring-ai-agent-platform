package com.example.agentplatform.tool;

import com.example.agentplatform.model.PlatformTool;

import java.util.List;

public final class PlatformToolCatalog {

    private PlatformToolCatalog() {
    }

    public static List<PlatformTool> builtins() {
        return List.of(
                timeTool("tool-time-convert-timezone", "time_convert_timezone", "时区转换", "时区转换",
                        "fa-solid fa-clock",
                        "将指定时间在不同时区（如北京、纽约、伦敦等）之间进行转换计算",
                        "将指定时间在不同时区之间进行换算转换。例如将北京时间转换为纽约时间、东京时间或伦敦时间。",
                        10),
                timeTool("tool-time-timestamp-converter", "time_timestamp_converter", "时间戳转换", "时间戳转换",
                        "fa-solid fa-clock",
                        "毫秒级/秒级 Unix 时间戳与标准日期时间字符串相互转换",
                        "Unix时间戳与格式化时间字符串之间的相互转换。可将秒级/毫秒级时间戳转为日期时间，或将日期时间转为时间戳。",
                        20),
                timeTool("tool-time-get-current-time", "time_get_current_time", "获取当前时间", "获取当前时间",
                        "fa-solid fa-clock",
                        "获取当前系统的精确年月日、时分秒与时区时间",
                        "获取指定时区的当前精确日期和时间（包含年月日、时分秒以及星期几）。当用户询问当前时间、现在几点、今天几号等问题时调用。",
                        30),
                timeTool("tool-time-date-calculator", "time_date_calculator", "获取时间戳", "获取时间戳",
                        "fa-solid fa-clock",
                        "计算日期偏移与两个日期相隔天数",
                        "计算两个日期之间相隔的天数，或者计算基准日期增加/减少若干天后的新日期。",
                        40),
                timeTool("tool-time-calculate-weekday", "time_calculate_weekday", "星期几计算器", "星期几计算器",
                        "fa-solid fa-calendar-days",
                        "计算历史上或未来的任意特定日期属于星期几",
                        "计算历史上或未来的某个具体日期是星期几。当用户询问某一天是周几或星期几时调用。",
                        50),
                searchTool()
        );
    }

    private static PlatformTool timeTool(String id, String code, String name, String title,
                                         String icon, String help, String description, int sort) {
        PlatformTool tool = new PlatformTool();
        tool.setId(id);
        tool.setCode(code);
        tool.setName(name);
        tool.setTitle(title);
        tool.setPrefix("time");
        tool.setCategory("TIME");
        tool.setIcon(icon);
        tool.setIconClass("icon-orange");
        tool.setHelp(help);
        tool.setDescription(description);
        tool.setConfigJson("{\"timezone\":\"Asia/Shanghai\",\"format\":\"yyyy-MM-dd HH:mm:ss\"}");
        tool.setEnabled(true);
        tool.setBuiltin(true);
        tool.setSortOrder(sort);
        return tool;
    }

    private static PlatformTool searchTool() {
        PlatformTool tool = new PlatformTool();
        tool.setId("tool-bocha-web-search");
        tool.setCode("bocha_web_search");
        tool.setName("联网检索");
        tool.setTitle("Bocha Web Search");
        tool.setPrefix("bocha");
        tool.setCategory("SEARCH");
        tool.setIcon("fa-solid fa-globe");
        tool.setIconClass("icon-bocha-badge");
        tool.setCustomIcon("bocha");
        tool.setHelp("博查 AI 搜索引擎，提供全网实时网页、新闻与知识检索");
        tool.setDescription("博查 AI 联网搜索引擎。当用户询问最新时事、实时天气、新闻事件、实时数据或任何需要获取最新互联网真实信息的场景时调用（注：系统时钟及今天几号等问题已有系统时间基准保障，无需调用本工具）。");
        tool.setConfigJson("{\"count\":5,\"freshness\":\"noLimit\",\"summary\":true}");
        tool.setEnabled(true);
        tool.setBuiltin(true);
        tool.setSortOrder(60);
        return tool;
    }
}
