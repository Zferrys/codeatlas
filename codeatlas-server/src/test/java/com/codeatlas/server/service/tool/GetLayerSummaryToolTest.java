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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetLayerSummaryTool 单元测试")
class GetLayerSummaryToolTest {

    private static final Long PROJECT_ID = 1L;

    @Mock
    private ClassSummaryMapper classSummaryMapper;

    private GetLayerSummaryTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetLayerSummaryTool(classSummaryMapper);
    }

    private static ClassSummaryEntity cls(String simpleName, String layer, Integer methods, Integer lines) {
        ClassSummaryEntity entity = new ClassSummaryEntity();
        entity.setFqn("com.demo." + simpleName);
        entity.setSimpleName(simpleName);
        entity.setLayer(layer);
        entity.setTotalMethods(methods);
        entity.setLineCount(lines);
        return entity;
    }

    @Test
    @DisplayName("getName 返回 get_layer_summary")
    void getName_shouldReturnFixedName() {
        assertEquals("get_layer_summary", tool.getName());
    }

    @Test
    @DisplayName("getDescription 描述了分层概览用途")
    void getDescription_shouldMentionLayerOverview() {
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("分层"));
    }

    @Test
    @DisplayName("getParametersSchema 为无参工具（object 且 properties 为空）")
    void getParametersSchema_shouldBeEmptyObjectSchema() {
        Map<String, Object> schema = tool.getParametersSchema();

        assertEquals("object", schema.get("type"));
        assertEquals(Collections.emptyMap(), schema.get("properties"));
        assertFalse(schema.containsKey("required"));
    }

    @Test
    @DisplayName("mapper 返回 null：提示先执行扫描")
    void execute_nullList_shouldReturnNoDataHint() {
        when(classSummaryMapper.findByProjectId(PROJECT_ID)).thenReturn(null);

        String result = tool.execute(Collections.emptyMap(), PROJECT_ID);

        assertEquals("(无数据) 该项目暂无类数据，请先执行扫描", result);
        verify(classSummaryMapper).findByProjectId(PROJECT_ID);
    }

    @Test
    @DisplayName("mapper 返回空列表：提示先执行扫描")
    void execute_emptyList_shouldReturnNoDataHint() {
        when(classSummaryMapper.findByProjectId(PROJECT_ID)).thenReturn(Collections.emptyList());

        String result = tool.execute(Collections.emptyMap(), PROJECT_ID);

        assertEquals("(无数据) 该项目暂无类数据，请先执行扫描", result);
    }

    @Test
    @DisplayName("单分层：按方法数降序排列，null 方法数按 0 处理，输出精确匹配")
    void execute_singleLayer_shouldSortByMethodCountDesc() {
        when(classSummaryMapper.findByProjectId(PROJECT_ID)).thenReturn(new ArrayList<>(Arrays.asList(
                cls("AlphaService", "service", 5, 50),
                cls("BetaService", "service", 9, 90),
                cls("GammaService", "service", null, null))));

        String result = tool.execute(null, PROJECT_ID);

        assertEquals("项目架构概览: 共 3 个类，1 个分层\n"
                + "\n"
                + "【service】3 个类\n"
                + "  - BetaService (方法9, 90行)\n"
                + "  - AlphaService (方法5, 50行)\n"
                + "  - GammaService (方法0, 0行)\n"
                + "\n", result);
    }

    @Test
    @DisplayName("layer 为 null 的类归入 unknown 分层")
    void execute_nullLayer_shouldGroupIntoUnknown() {
        when(classSummaryMapper.findByProjectId(PROJECT_ID)).thenReturn(new ArrayList<>(Collections.singletonList(
                cls("MysteryClass", null, 2, 20))));

        String result = tool.execute(Collections.emptyMap(), PROJECT_ID);

        assertEquals("项目架构概览: 共 1 个类，1 个分层\n"
                + "\n"
                + "【unknown】1 个类\n"
                + "  - MysteryClass (方法2, 20行)\n"
                + "\n", result);
    }

    @Test
    @DisplayName("分层内超过 5 个类：只列前 5 个并提示其余数量")
    void execute_moreThanFiveClasses_shouldTruncateWithRemainderNote() {
        List<ClassSummaryEntity> classes = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            classes.add(cls("C" + i, "service", i * 10, i * 100));
        }
        when(classSummaryMapper.findByProjectId(PROJECT_ID)).thenReturn(classes);

        String result = tool.execute(Collections.emptyMap(), PROJECT_ID);

        assertEquals("项目架构概览: 共 7 个类，1 个分层\n"
                + "\n"
                + "【service】7 个类\n"
                + "  - C7 (方法70, 700行)\n"
                + "  - C6 (方法60, 600行)\n"
                + "  - C5 (方法50, 500行)\n"
                + "  - C4 (方法40, 400行)\n"
                + "  - C3 (方法30, 300行)\n"
                + "  ... 及其他 2 个类\n"
                + "\n", result);
    }

    @Test
    @DisplayName("恰好 5 个类：全部列出，不出现截断提示")
    void execute_exactlyFiveClasses_shouldNotTruncate() {
        List<ClassSummaryEntity> classes = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            classes.add(cls("C" + i, "service", i * 10, i * 100));
        }
        when(classSummaryMapper.findByProjectId(PROJECT_ID)).thenReturn(classes);

        String result = tool.execute(Collections.emptyMap(), PROJECT_ID);

        assertTrue(result.contains("  - C1 (方法10, 100行)\n"));
        assertFalse(result.contains("及其他"));
    }

    @Test
    @DisplayName("多个分层：头部分层数统计正确，每个分层块都输出")
    void execute_multipleLayers_shouldRenderAllLayerBlocks() {
        when(classSummaryMapper.findByProjectId(PROJECT_ID)).thenReturn(new ArrayList<>(Arrays.asList(
                cls("UserController", "controller", 4, 40),
                cls("UserService", "service", 6, 60),
                cls("UserMapper", "dao", 2, 20))));

        String result = tool.execute(Collections.emptyMap(), PROJECT_ID);

        assertTrue(result.startsWith("项目架构概览: 共 3 个类，3 个分层\n\n"));
        assertTrue(result.contains("【controller】1 个类\n  - UserController (方法4, 40行)\n\n"));
        assertTrue(result.contains("【service】1 个类\n  - UserService (方法6, 60行)\n\n"));
        assertTrue(result.contains("【dao】1 个类\n  - UserMapper (方法2, 20行)\n\n"));
    }
}
