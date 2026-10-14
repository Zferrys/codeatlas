package com.codeatlas.engine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@DisplayName("PromptTemplate — {{key}} 占位符替换与特殊字符转义")
class PromptTemplateTest {

    @Test
    @DisplayName("替换值含反斜杠与 $ 时原样输出，不触发 Illegal group reference")
    void render_valueWithBackslashAndDollar_shouldBeLiteral() {
        PromptTemplate template = new PromptTemplate("prompts/test-escape.md");
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "C:\\temp\\x");
        vars.put("path", "$HOME $(cmd) $1");

        String rendered = template.render(vars);

        // 修复前：appendReplacement 会把 \ 和 $ 当转义序列处理 —— \x 丢字符、$HOME 抛 Illegal group reference
        assertEquals("Name: C:\\temp\\x\nPath: $HOME $(cmd) $1\n", rendered);
    }

    @Test
    @DisplayName("未提供的占位符替换为空串")
    void render_missingVariable_shouldReplaceWithEmpty() {
        PromptTemplate template = new PromptTemplate("prompts/test-escape.md");

        String rendered = template.render(new HashMap<>());

        assertEquals("Name: \nPath: \n", rendered);
    }

    @Test
    @DisplayName("模板文件不存在时返回空串")
    void render_missingTemplate_shouldReturnEmpty() {
        PromptTemplate template = new PromptTemplate("prompts/definitely-not-exists.md");

        assertEquals("", template.render(new HashMap<>()));
    }

    @Test
    @DisplayName("getTemplatePath 返回构造时的原始路径（含未找到的模板）")
    void getTemplatePath_shouldReturnOriginalPath() {
        PromptTemplate existing = new PromptTemplate("prompts/test-escape.md");
        PromptTemplate missing = new PromptTemplate("prompts/definitely-not-exists.md");

        assertEquals("prompts/test-escape.md", existing.getTemplatePath());
        assertEquals("prompts/definitely-not-exists.md", missing.getTemplatePath());
    }

    @Test
    @DisplayName("同名模板重复实例化命中缓存：内容一致且不重复加载")
    void render_cachedTemplate_shouldProduceSameResult() {
        PromptTemplate first = new PromptTemplate("prompts/test-escape.md");
        PromptTemplate second = new PromptTemplate("prompts/test-escape.md");
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "N");
        vars.put("path", "P");

        assertEquals(first.render(vars), second.render(vars));
        assertEquals("Name: N\nPath: P\n", second.render(vars));
    }

    @Test
    @DisplayName("读取模板抛 IOException：catch 记录错误并缓存空串，render 返回空串")
    void render_ioErrorWhileReading_shouldCacheEmptyAndReturnEmpty() throws Exception {
        clearTemplateCache(); // 清掉历史缓存，确保真实进入 loadTemplate 的读取分支
        try (MockedConstruction<InputStreamReader> constructed =
                     mockConstruction(InputStreamReader.class, (mock, context) ->
                             when(mock.read(any(char[].class), anyInt(), anyInt()))
                                     .thenThrow(new IOException("stream broken")))) {
            PromptTemplate template = new PromptTemplate("prompts/test-escape.md");

            assertEquals(1, constructed.constructed().size(), "应恰好构造 1 个 InputStreamReader");
            assertEquals("", template.render(new HashMap<>()));
            assertEquals("", templateCache().get("prompts/test-escape.md"),
                    "读取失败的结果也被缓存为空串，后续同名实例化不再重试");
        } finally {
            clearTemplateCache(); // 移除污染的空串缓存，避免影响其他用例加载真实模板
        }
    }

    // ==================== CACHE 反射辅助 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, String> templateCache() throws Exception {
        Field field = PromptTemplate.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    private static void clearTemplateCache() throws Exception {
        templateCache().clear();
    }
}
