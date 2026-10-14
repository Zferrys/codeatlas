package com.codeatlas.server.service.tool;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchClassesTool 单元测试")
class SearchClassesToolTest {

    private static final Long PROJECT_ID = 1L;

    @Mock
    private ClassSummaryMapper classSummaryMapper;

    private SearchClassesTool tool;

    @BeforeEach
    void setUp() {
        tool = new SearchClassesTool(classSummaryMapper);
    }

    private static ClassSummaryEntity cls(String fqn, String layer, Integer methods, Integer lines) {
        ClassSummaryEntity entity = new ClassSummaryEntity();
        entity.setFqn(fqn);
        entity.setSimpleName(fqn.substring(fqn.lastIndexOf('.') + 1));
        entity.setLayer(layer);
        entity.setTotalMethods(methods);
        entity.setLineCount(lines);
        return entity;
    }

    private static Map<String, Object> args(String keyword) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("keyword", keyword);
        return arguments;
    }

    @Test
    @DisplayName("getName 返回 search_classes")
    void getName_shouldReturnFixedName() {
        assertEquals("search_classes", tool.getName());
    }

    @Test
    @DisplayName("getDescription 描述了关键词搜索用途")
    void getDescription_shouldMentionKeywordSearch() {
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("搜索"));
    }

    @Test
    @DisplayName("getParametersSchema 要求 keyword 必填且为 string")
    void getParametersSchema_shouldRequireKeyword() {
        Map<String, Object> schema = tool.getParametersSchema();

        assertEquals("object", schema.get("type"));
        assertEquals(Collections.singletonList("keyword"), schema.get("required"));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props);
        assertEquals(1, props.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> keyword = (Map<String, Object>) props.get("keyword");
        assertEquals("string", keyword.get("type"));
        assertTrue(String.valueOf(keyword.get("description")).contains("关键词"));
    }

    @Test
    @DisplayName("arguments 为 null 时返回缺参提示且不查库")
    void execute_nullArguments_shouldReturnParameterHint() {
        String result = tool.execute(null, PROJECT_ID);

        assertEquals("(无结果) 请提供 keyword 参数", result);
        verifyNoInteractions(classSummaryMapper);
    }

    @Test
    @DisplayName("缺少 keyword 键时返回缺参提示且不查库")
    void execute_missingKeywordKey_shouldReturnParameterHint() {
        String result = tool.execute(new HashMap<>(), PROJECT_ID);

        assertEquals("(无结果) 请提供 keyword 参数", result);
        verifyNoInteractions(classSummaryMapper);
    }

    @Test
    @DisplayName("keyword 为纯空白时返回缺参提示且不查库")
    void execute_blankKeyword_shouldReturnParameterHint() {
        String result = tool.execute(args("  \t "), PROJECT_ID);

        assertEquals("(无结果) 请提供 keyword 参数", result);
        verifyNoInteractions(classSummaryMapper);
    }

    @Test
    @DisplayName("无匹配结果：提示换关键词（保留原始入参）且按 trim 后查询、limit 100")
    void execute_noResults_shouldReturnNoResultHint() {
        when(classSummaryMapper.searchByKeyword(PROJECT_ID, "ghost", 100)).thenReturn(Collections.emptyList());

        String result = tool.execute(args("  ghost  "), PROJECT_ID);

        assertEquals("(无结果) 未找到包含 \"  ghost  \" 的类，尝试换一个关键词", result);
        verify(classSummaryMapper).searchByKeyword(PROJECT_ID, "ghost", 100);
    }

    @Test
    @DisplayName("单条结果：编号、分层、方法数、行数逐字输出；null 字段兜底默认值")
    void execute_singleResult_shouldRenderEntryWithDefaults() {
        when(classSummaryMapper.searchByKeyword(PROJECT_ID, "Payment", 100))
                .thenReturn(new ArrayList<>(Collections.singletonList(
                        cls("com.demo.PaymentService", null, null, null))));

        String result = tool.execute(args("Payment"), PROJECT_ID);

        assertEquals("找到 1 个匹配类:\n"
                + "1. com.demo.PaymentService [unknown] 方法0 行0\n", result);
    }

    @Test
    @DisplayName("恰好 10 条结果：全部输出")
    void execute_tenResults_shouldRenderAll() {
        List<ClassSummaryEntity> results = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            results.add(cls("com.demo.Svc" + i, "service", i, i * 10));
        }
        when(classSummaryMapper.searchByKeyword(PROJECT_ID, "Svc", 100)).thenReturn(results);

        String result = tool.execute(args("Svc"), PROJECT_ID);

        assertEquals("找到 10 个匹配类:\n"
                + "1. com.demo.Svc1 [service] 方法1 行10\n"
                + "2. com.demo.Svc2 [service] 方法2 行20\n"
                + "3. com.demo.Svc3 [service] 方法3 行30\n"
                + "4. com.demo.Svc4 [service] 方法4 行40\n"
                + "5. com.demo.Svc5 [service] 方法5 行50\n"
                + "6. com.demo.Svc6 [service] 方法6 行60\n"
                + "7. com.demo.Svc7 [service] 方法7 行70\n"
                + "8. com.demo.Svc8 [service] 方法8 行80\n"
                + "9. com.demo.Svc9 [service] 方法9 行90\n"
                + "10. com.demo.Svc10 [service] 方法10 行100\n", result);
    }

    @Test
    @DisplayName("12 条结果：头部显示总数，只列出前 10 条")
    void execute_moreThanTenResults_shouldShowTotalAndLimitList() {
        List<ClassSummaryEntity> results = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            results.add(cls("com.demo.Svc" + i, "service", i, i * 10));
        }
        when(classSummaryMapper.searchByKeyword(PROJECT_ID, "Svc", 100)).thenReturn(results);

        String result = tool.execute(args("Svc"), PROJECT_ID);

        assertTrue(result.startsWith("找到 12 个匹配类:\n"));
        assertTrue(result.contains("10. com.demo.Svc10 [service] 方法10 行100\n"));
        assertFalse(result.contains("11. com.demo.Svc11"));
        assertFalse(result.contains("12. com.demo.Svc12"));
    }
}
