package com.codeatlas.server.config;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.ClaudeClient;
import com.codeatlas.engine.ai.DeepSeekClient;
import com.codeatlas.engine.git.GitService;
import com.codeatlas.engine.parser.JavaParserService;
import com.codeatlas.server.mapper.SystemConfigMapper;
import com.codeatlas.server.service.AiModelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EngineBeanConfig 单元测试。
 *
 * aiModelService 的装配分支由进程环境变量 ANTHROPIC_API_KEY / DEEPSEEK_API_KEY 决定，
 * 而 JDK 17 下环境变量表不可修改；Mockito 也明确拒绝 mock java.lang.System
 * （2026-10-14 实测抛 MockitoException："not possible to mock static methods of
 * java.lang.System ... infinite loops"）。
 * 因此该用例读取真实环境变量并推导精确期望（在任意环境组合下都断言真实行为）：
 * - 常规跑（本机未设 ANTHROPIC_API_KEY）：Claude 分支跳过，走 DeepSeek 兜底路径；
 * - 全分支覆盖跑（mvn 命令前缀 ANTHROPIC_API_KEY=<假值>）：Claude 优先路径亦被完整执行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EngineBeanConfig 单元测试（engine Bean 装配 + API Key 环境变量分支）")
class EngineBeanConfigTest {

    private static final String CLAUDE_KEY = System.getenv("ANTHROPIC_API_KEY");
    private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

    @Mock
    private SystemConfigMapper systemConfigMapper;

    private final EngineBeanConfig config = new EngineBeanConfig();

    private static boolean configured(String key) {
        return key != null && !key.isEmpty();
    }

    private AiModelService buildService() {
        return config.aiModelService(systemConfigMapper,
                "https://claude.test", "https://deepseek.test",
                "claude-model-x", "deepseek-model-y");
    }

    @Test
    @DisplayName("gitService/javaParserService 返回 engine 模块实例")
    void engineBeans_returnEngineInstances() {
        assertInstanceOf(GitService.class, config.gitService());
        assertInstanceOf(JavaParserService.class, config.javaParserService());
    }

    @Test
    @DisplayName("aiModelService：按环境变量装配模型链 —— Claude 优先、DeepSeek 兜底、Key 空串等同未配置")
    void aiModelService_registersModelsAccordingToEnvironment() {
        boolean hasClaude = configured(CLAUDE_KEY);
        boolean hasDeepseek = configured(DEEPSEEK_KEY);

        AiModelService service = buildService();

        assertEquals(hasClaude || hasDeepseek, service.hasAnyModel());

        String expectedCurrent = hasClaude ? "claude-model-x" : (hasDeepseek ? "deepseek-model-y" : "");
        assertEquals(expectedCurrent, service.getCurrentModelId());

        List<AiClient> chain = service.resolveChain();
        assertEquals((hasClaude ? 1 : 0) + (hasDeepseek ? 1 : 0), chain.size());

        int index = 0;
        if (hasClaude) {
            assertInstanceOf(ClaudeClient.class, chain.get(index));
            assertEquals("claude-model-x", chain.get(index).getModelName());
            index++;
        }
        if (hasDeepseek) {
            assertInstanceOf(DeepSeekClient.class, chain.get(index));
            assertEquals("deepseek-model-y", chain.get(index).getModelName());
        }
    }

    @Test
    @DisplayName("aiClient：无可用模型时返回 null（AI 分析不可用）")
    void aiClient_noModel_returnsNull() {
        AiModelService service = mock(AiModelService.class);
        when(service.hasAnyModel()).thenReturn(false);

        assertNull(config.aiClient(service));

        verify(service, times(1)).hasAnyModel();
    }

    @Test
    @DisplayName("aiClient：有可用模型时返回 AiModelRouter 路由")
    void aiClient_withModel_returnsRouter() {
        AiModelService service = mock(AiModelService.class);
        when(service.hasAnyModel()).thenReturn(true);
        when(service.getCurrentModelId()).thenReturn("claude-model-x");

        AiClient client = config.aiClient(service);

        assertInstanceOf(AiModelRouter.class, client);
        assertEquals("claude-model-x", client.getModelName());
        verify(service, times(1)).hasAnyModel();
        // 一次用于装配日志，一次经 router.getModelName() 透传
        verify(service, times(2)).getCurrentModelId();
    }
}
