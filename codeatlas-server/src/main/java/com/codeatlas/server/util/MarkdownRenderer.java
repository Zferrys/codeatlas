package com.codeatlas.server.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迷你 Markdown 渲染器 — 仅覆盖架构叙事实际用到的语法：
 * 标题（# ~ ####）、无序/有序列表、段落、**加粗**、`行内代码`。
 * 先转义再注入标签，避免叙事内容造成 XSS。
 */
public final class MarkdownRenderer {

    /** 报告正文已有 h1 标题，叙事标题整体下沉一级 */
    private static final int HEADING_LEVEL_SHIFT = 1;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UNORDERED_ITEM = Pattern.compile("^[-*]\\s+(.*)$");
    private static final Pattern ORDERED_ITEM = Pattern.compile("^\\d+\\.\\s+(.*)$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");

    private MarkdownRenderer() {
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        String openList = null; // 当前打开的列表标签：ul / ol / null
        for (String rawLine : markdown.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                openList = closeList(html, openList);
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            Matcher unordered = UNORDERED_ITEM.matcher(line);
            Matcher ordered = ORDERED_ITEM.matcher(line);
            if (heading.matches()) {
                openList = closeList(html, openList);
                int level = Math.min(heading.group(1).length() + HEADING_LEVEL_SHIFT, 6);
                html.append("<h").append(level).append(">")
                        .append(renderInline(heading.group(2)))
                        .append("</h").append(level).append(">");
            } else if (unordered.matches()) {
                openList = openList(html, openList, "ul");
                html.append("<li>").append(renderInline(unordered.group(1))).append("</li>");
            } else if (ordered.matches()) {
                openList = openList(html, openList, "ol");
                html.append("<li>").append(renderInline(ordered.group(1))).append("</li>");
            } else {
                openList = closeList(html, openList);
                html.append("<p>").append(renderInline(line)).append("</p>");
            }
        }
        closeList(html, openList);
        return html.toString();
    }

    private static String openList(StringBuilder html, String openList, String wanted) {
        if (wanted.equals(openList)) {
            return openList;
        }
        closeList(html, openList);
        html.append("<").append(wanted).append(">");
        return wanted;
    }

    private static String closeList(StringBuilder html, String openList) {
        if (openList != null) {
            html.append("</").append(openList).append(">");
        }
        return null;
    }

    private static String renderInline(String text) {
        String escaped = escapeHtml(text);
        escaped = BOLD.matcher(escaped).replaceAll("<strong>$1</strong>");
        return INLINE_CODE.matcher(escaped).replaceAll("<code>$1</code>");
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
