package com.codeatlas.server.service.tool;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetClassDetailTool 单元测试")
class GetClassDetailToolTest {

    private static final Long PROJECT_ID = 1L;
    private static final String FQN = "com.demo.service.PaymentService";

    @Mock
    private ClassSummaryMapper classSummaryMapper;

    private GetClassDetailTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetClassDetailTool(classSummaryMapper);
    }

    @Test
    @DisplayName("getName 返回 get_class_detail")
    void getName_shouldReturnFixedName() {
        assertEquals("get_class_detail", tool.getName());
    }

    @Test
    @DisplayName("getDescription 描述了获取类详情的用途")
    void getDescription_shouldMentionClassDetail() {
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("详细信息"));
        assertTrue(tool.getDescription().contains("fqn"));
    }

    @Test
    @DisplayName("getParametersSchema 要求 fqn 必填且为 string")
    void getParametersSchema_shouldRequireFqn() {
        Map<String, Object> schema = tool.getParametersSchema();

        assertEquals("object", schema.get("type"));
        assertEquals(Collections.singletonList("fqn"), schema.get("required"));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props);
        assertEquals(1, props.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> fqn = (Map<String, Object>) props.get("fqn");
        assertEquals("string", fqn.get("type"));
        assertTrue(String.valueOf(fqn.get("description")).contains("全限定名"));
    }

    @Test
    @DisplayName("arguments 为 null 时返回缺参错误且不查库")
    void execute_nullArguments_shouldReturnParameterError() {
        String result = tool.execute(null, PROJECT_ID);

        assertEquals("(错误) 请提供 fqn 参数", result);
        verifyNoInteractions(classSummaryMapper);
    }

    @Test
    @DisplayName("缺少 fqn 键时返回缺参错误且不查库")
    void execute_missingFqnKey_shouldReturnParameterError() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("other", "x");

        String result = tool.execute(arguments, PROJECT_ID);

        assertEquals("(错误) 请提供 fqn 参数", result);
        verifyNoInteractions(classSummaryMapper);
    }

    @Test
    @DisplayName("fqn 为纯空白时返回缺参错误且不查库")
    void execute_blankFqn_shouldReturnParameterError() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("fqn", "   ");

        String result = tool.execute(arguments, PROJECT_ID);

        assertEquals("(错误) 请提供 fqn 参数", result);
        verifyNoInteractions(classSummaryMapper);
    }

    @Test
    @DisplayName("未找到类时返回无结果提示（保留用户原始入参）且按 trim 后查询")
    void execute_classNotFound_shouldReturnNoResult() {
        when(classSummaryMapper.findByProjectIdAndFqn(PROJECT_ID, FQN)).thenReturn(null);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("fqn", "  " + FQN + "  ");

        String result = tool.execute(arguments, PROJECT_ID);

        assertEquals("(无结果) 未找到类 \"  " + FQN + "  \"，请检查类名是否正确", result);
        verify(classSummaryMapper).findByProjectIdAndFqn(PROJECT_ID, FQN);
    }

    @Test
    @DisplayName("全字段类：输出完整详情，逐行精确匹配")
    void execute_fullEntity_shouldRenderAllSections() {
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn(FQN);
        cls.setSimpleName("PaymentService");
        cls.setPackageName("com.demo.service");
        cls.setClassType("interface");
        cls.setLayer("service");
        cls.setPublicMethods(3);
        cls.setTotalMethods(5);
        cls.setLineCount(42);
        cls.setAnnotations("[\"@Service\"]");
        cls.setMethodNames("[\"pay\",\"refund\"]");
        cls.setDependencies("[\"com.demo.dao.OrderDao\"]");
        cls.setModuleName("demo-core");
        when(classSummaryMapper.findByProjectIdAndFqn(PROJECT_ID, FQN)).thenReturn(cls);

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("fqn", FQN);
        String result = tool.execute(arguments, PROJECT_ID);

        assertEquals("类详情:\n"
                + "- 全限定名: " + FQN + "\n"
                + "- 简单类名: PaymentService\n"
                + "- 包名: com.demo.service\n"
                + "- 类型: interface\n"
                + "- 分层: service\n"
                + "- 公开方法数: 3\n"
                + "- 总方法数: 5\n"
                + "- 代码行数: 42\n"
                + "- 注解: [\"@Service\"]\n"
                + "- 方法列表: [\"pay\",\"refund\"]\n"
                + "- 依赖列表: [\"com.demo.dao.OrderDao\"]\n"
                + "- 所属模块: demo-core\n", result);
    }

    @Test
    @DisplayName("可空字段为 null：类型/分层取默认值，方法数与行数兜底 0，可选项不输出")
    void execute_nullableFieldsNull_shouldUseDefaults() {
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn(FQN);
        cls.setSimpleName("PaymentService");
        cls.setPackageName("com.demo.service");
        cls.setClassType(null);
        cls.setLayer(null);
        cls.setPublicMethods(null);
        cls.setTotalMethods(null);
        cls.setLineCount(null);
        cls.setAnnotations(null);
        cls.setMethodNames(null);
        cls.setDependencies(null);
        cls.setModuleName(null);
        when(classSummaryMapper.findByProjectIdAndFqn(PROJECT_ID, FQN)).thenReturn(cls);

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("fqn", FQN);
        String result = tool.execute(arguments, PROJECT_ID);

        assertEquals("类详情:\n"
                + "- 全限定名: " + FQN + "\n"
                + "- 简单类名: PaymentService\n"
                + "- 包名: com.demo.service\n"
                + "- 类型: class\n"
                + "- 分层: unknown\n"
                + "- 公开方法数: 0\n"
                + "- 总方法数: 0\n"
                + "- 代码行数: 0\n", result);
    }

    @Test
    @DisplayName("可空字段为空字符串：注解/方法/依赖/模块均不输出")
    void execute_emptyStringFields_shouldOmitSections() {
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn(FQN);
        cls.setSimpleName("PaymentService");
        cls.setPackageName("com.demo.service");
        cls.setAnnotations("");
        cls.setMethodNames("");
        cls.setDependencies("");
        cls.setModuleName("");
        when(classSummaryMapper.findByProjectIdAndFqn(PROJECT_ID, FQN)).thenReturn(cls);

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("fqn", FQN);
        String result = tool.execute(arguments, PROJECT_ID);

        assertEquals("类详情:\n"
                + "- 全限定名: " + FQN + "\n"
                + "- 简单类名: PaymentService\n"
                + "- 包名: com.demo.service\n"
                + "- 类型: class\n"
                + "- 分层: unknown\n"
                + "- 公开方法数: 0\n"
                + "- 总方法数: 0\n"
                + "- 代码行数: 0\n", result);
    }

    @Test
    @DisplayName("可空字段为 \"[]\" 空数组标记：注解/方法/依赖均不输出")
    void execute_emptyJsonArrayFields_shouldOmitSections() {
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn(FQN);
        cls.setSimpleName("PaymentService");
        cls.setPackageName("com.demo.service");
        cls.setAnnotations("[]");
        cls.setMethodNames("[]");
        cls.setDependencies("[]");
        cls.setModuleName(null);
        when(classSummaryMapper.findByProjectIdAndFqn(PROJECT_ID, FQN)).thenReturn(cls);

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("fqn", FQN);
        String result = tool.execute(arguments, PROJECT_ID);

        assertEquals("类详情:\n"
                + "- 全限定名: " + FQN + "\n"
                + "- 简单类名: PaymentService\n"
                + "- 包名: com.demo.service\n"
                + "- 类型: class\n"
                + "- 分层: unknown\n"
                + "- 公开方法数: 0\n"
                + "- 总方法数: 0\n"
                + "- 代码行数: 0\n", result);
    }

    @Test
    @DisplayName("仅模块名非空：只额外输出所属模块一行")
    void execute_onlyModuleName_shouldRenderModuleLine() {
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn(FQN);
        cls.setSimpleName("PaymentService");
        cls.setPackageName("com.demo.service");
        cls.setModuleName("demo-api");
        when(classSummaryMapper.findByProjectIdAndFqn(PROJECT_ID, FQN)).thenReturn(cls);

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("fqn", FQN);
        String result = tool.execute(arguments, PROJECT_ID);

        assertEquals("类详情:\n"
                + "- 全限定名: " + FQN + "\n"
                + "- 简单类名: PaymentService\n"
                + "- 包名: com.demo.service\n"
                + "- 类型: class\n"
                + "- 分层: unknown\n"
                + "- 公开方法数: 0\n"
                + "- 总方法数: 0\n"
                + "- 代码行数: 0\n"
                + "- 所属模块: demo-api\n", result);
    }
}
