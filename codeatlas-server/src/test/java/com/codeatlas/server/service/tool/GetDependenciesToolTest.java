package com.codeatlas.server.service.tool;

import com.codeatlas.server.service.Neo4jGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
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
@DisplayName("GetDependenciesTool 单元测试")
class GetDependenciesToolTest {

    private static final Long PROJECT_ID = 1L;
    private static final String FQN = "com.demo.service.PaymentService";

    @Mock
    private Neo4jGraphService neo4jGraphService;

    private GetDependenciesTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetDependenciesTool(neo4jGraphService);
    }

    private static Map<String, Object> args(String fqn, String direction) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("fqn", fqn);
        if (direction != null) {
            arguments.put("direction", direction);
        }
        return arguments;
    }

    @Test
    @DisplayName("getName 返回 get_dependencies")
    void getName_shouldReturnFixedName() {
        assertEquals("get_dependencies", tool.getName());
    }

    @Test
    @DisplayName("getDescription 说明三种依赖方向")
    void getDescription_shouldMentionDirections() {
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("outgoing"));
        assertTrue(tool.getDescription().contains("incoming"));
        assertTrue(tool.getDescription().contains("both"));
    }

    @Test
    @DisplayName("getParametersSchema 要求 fqn 必填，direction 枚举三值")
    void getParametersSchema_shouldDeclareFqnAndDirectionEnum() {
        Map<String, Object> schema = tool.getParametersSchema();

        assertEquals("object", schema.get("type"));
        assertEquals(Collections.singletonList("fqn"), schema.get("required"));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props);
        assertEquals(2, props.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> fqn = (Map<String, Object>) props.get("fqn");
        assertEquals("string", fqn.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) props.get("direction");
        assertEquals("string", direction.get("type"));
        assertEquals(Arrays.asList("outgoing", "incoming", "both"), direction.get("enum"));
    }

    @Test
    @DisplayName("arguments 为 null 时返回缺参错误且不查图数据库")
    void execute_nullArguments_shouldReturnParameterError() {
        String result = tool.execute(null, PROJECT_ID);

        assertEquals("(错误) 请提供 fqn 参数", result);
        verifyNoInteractions(neo4jGraphService);
    }

    @Test
    @DisplayName("缺少 fqn 键时返回缺参错误且不查图数据库")
    void execute_missingFqnKey_shouldReturnParameterError() {
        String result = tool.execute(new HashMap<>(), PROJECT_ID);

        assertEquals("(错误) 请提供 fqn 参数", result);
        verifyNoInteractions(neo4jGraphService);
    }

    @Test
    @DisplayName("fqn 为纯空白时返回缺参错误且不查图数据库")
    void execute_blankFqn_shouldReturnParameterError() {
        String result = tool.execute(args("   ", null), PROJECT_ID);

        assertEquals("(错误) 请提供 fqn 参数", result);
        verifyNoInteractions(neo4jGraphService);
    }

    @Test
    @DisplayName("未提供 direction 时默认为 both，fqn 按 trim 查询，结果原样返回")
    void execute_missingDirection_shouldDefaultToBoth() {
        when(neo4jGraphService.queryDirectNeighbors(PROJECT_ID, FQN, "both")).thenReturn("双向依赖结果");

        String result = tool.execute(args("  " + FQN + "  ", null), PROJECT_ID);

        assertEquals("双向依赖结果", result);
        verify(neo4jGraphService).queryDirectNeighbors(PROJECT_ID, FQN, "both");
    }

    @Test
    @DisplayName("显式 direction=outgoing 原样透传")
    void execute_explicitOutgoing_shouldPassThrough() {
        when(neo4jGraphService.queryDirectNeighbors(PROJECT_ID, FQN, "outgoing")).thenReturn("出向依赖结果");

        String result = tool.execute(args(FQN, "outgoing"), PROJECT_ID);

        assertEquals("出向依赖结果", result);
        verify(neo4jGraphService).queryDirectNeighbors(PROJECT_ID, FQN, "outgoing");
    }

    @Test
    @DisplayName("direction 为空字符串（非 null）不做兜底，原样透传")
    void execute_emptyDirection_shouldPassThroughUnchanged() {
        when(neo4jGraphService.queryDirectNeighbors(PROJECT_ID, FQN, "")).thenReturn("空方向结果");

        String result = tool.execute(args(FQN, ""), PROJECT_ID);

        assertEquals("空方向结果", result);
        verify(neo4jGraphService).queryDirectNeighbors(PROJECT_ID, FQN, "");
    }

    @Test
    @DisplayName("图数据库返回 null 时原样返回 null")
    void execute_graphReturnsNull_shouldReturnNull() {
        when(neo4jGraphService.queryDirectNeighbors(PROJECT_ID, FQN, "incoming")).thenReturn(null);

        String result = tool.execute(args(FQN, "incoming"), PROJECT_ID);

        assertEquals(null, result);
        verify(neo4jGraphService).queryDirectNeighbors(PROJECT_ID, FQN, "incoming");
    }
}
