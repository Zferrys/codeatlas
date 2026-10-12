package com.codeatlas.server.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    @Test
    void rendersHeadingsWithLevelShift() {
        assertEquals("<h2>架构叙事报告</h2>", MarkdownRenderer.toHtml("# 架构叙事报告"));
        assertEquals("<h3>1. 项目概览</h3>", MarkdownRenderer.toHtml("## 1. 项目概览"));
        assertEquals("<h4>决策一</h4>", MarkdownRenderer.toHtml("### 决策一"));
    }

    @Test
    void rendersBoldAndInlineCode() {
        String html = MarkdownRenderer.toHtml("**做了什么**：使用 `ProjectService` 接口");
        assertEquals("<p><strong>做了什么</strong>：使用 <code>ProjectService</code> 接口</p>", html);
    }

    @Test
    void rendersUnorderedAndOrderedLists() {
        assertEquals("<ul><li>第一项</li><li>第二项</li></ul>",
                MarkdownRenderer.toHtml("- 第一项\n- 第二项"));
        assertEquals("<ol><li>接收请求</li><li>调用服务</li></ol>",
                MarkdownRenderer.toHtml("1. 接收请求\n2. 调用服务"));
    }

    @Test
    void switchesBetweenListTypes() {
        assertEquals("<ul><li>项</li></ul><ol><li>步</li></ol>",
                MarkdownRenderer.toHtml("- 项\n1. 步"));
    }

    @Test
    void closesListBeforeParagraph() {
        assertEquals("<ul><li>列表项</li></ul><p>普通段落</p>",
                MarkdownRenderer.toHtml("- 列表项\n\n普通段落"));
    }

    @Test
    void escapesHtmlInSource() {
        String html = MarkdownRenderer.toHtml("<script>alert('x')</script> & 内容");
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals("", MarkdownRenderer.toHtml(null));
        assertEquals("", MarkdownRenderer.toHtml(""));
    }
}
