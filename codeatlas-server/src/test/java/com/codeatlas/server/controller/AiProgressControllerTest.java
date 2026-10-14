package com.codeatlas.server.controller;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.AiTaskProgressRegistry;
import com.codeatlas.server.service.ProjectAccessChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiProgressController 单元测试")
class AiProgressControllerTest {

    private static final Long PROJECT_ID = 4L;
    private static final Long USER_ID = 6L;

    @Mock
    private AiTaskProgressRegistry progressRegistry;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    private AiProgressController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new AiProgressController(progressRegistry, projectAccessChecker);
        principal = new CodeAtlasUserDetails(USER_ID, "ivy", "DEVELOPER");
    }

    @Test
    @DisplayName("aiProgress — 合法 taskId 通过访问校验后订阅并原样返回 emitter")
    void aiProgressSubscribes() {
        SseEmitter emitter = new SseEmitter();
        when(progressRegistry.subscribe("aitask-12345678", PROJECT_ID)).thenReturn(emitter);

        SseEmitter result = controller.aiProgress(PROJECT_ID, "aitask-12345678", principal);

        assertSame(emitter, result);
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        verify(progressRegistry).subscribe("aitask-12345678", PROJECT_ID);
    }

    @Test
    @DisplayName("aiProgress — 下划线/大写/数字字符组合合法")
    void aiProgressAcceptsFullCharset() {
        when(progressRegistry.subscribe("ABC_def-123", PROJECT_ID)).thenReturn(new SseEmitter());

        controller.aiProgress(PROJECT_ID, "ABC_def-123", principal);

        verify(progressRegistry).subscribe("ABC_def-123", PROJECT_ID);
    }

    @Test
    @DisplayName("aiProgress — taskId 为 null 抛 400 并且不订阅")
    void aiProgressRejectsNullTaskId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.aiProgress(PROJECT_ID, null, principal));

        assertEquals(400, ex.getCode());
        assertEquals("taskId 无效", ex.getMessage());
        verify(progressRegistry, never()).subscribe(any(), any());
    }

    @Test
    @DisplayName("aiProgress — taskId 过短（7 位）抛 400")
    void aiProgressRejectsShortTaskId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.aiProgress(PROJECT_ID, "1234567", principal));

        assertEquals(400, ex.getCode());
        assertEquals("taskId 无效", ex.getMessage());
        verify(progressRegistry, never()).subscribe(any(), any());
    }

    @Test
    @DisplayName("aiProgress — taskId 超长（65 位）抛 400")
    void aiProgressRejectsLongTaskId() {
        String tooLong = "a".repeat(65);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.aiProgress(PROJECT_ID, tooLong, principal));

        assertEquals(400, ex.getCode());
        assertEquals("taskId 无效", ex.getMessage());
        verify(progressRegistry, never()).subscribe(any(), any());
    }

    @Test
    @DisplayName("aiProgress — 含非法字符（点号/空格）抛 400")
    void aiProgressRejectsIllegalChars() {
        for (String invalid : new String[]{"aitask.1234", "aitask 1234", "aitask@1234", "任务aitask1"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> controller.aiProgress(PROJECT_ID, invalid, principal));
            assertEquals(400, ex.getCode());
            assertEquals("taskId 无效", ex.getMessage());
        }
        verify(progressRegistry, never()).subscribe(any(), any());
    }

    @Test
    @DisplayName("aiProgress — 边界长度 8 与 64 均合法")
    void aiProgressAcceptsBoundaryLengths() {
        String eight = "a".repeat(8);
        String sixtyFour = "b".repeat(64);
        when(progressRegistry.subscribe(eq(eight), eq(PROJECT_ID))).thenReturn(new SseEmitter());
        when(progressRegistry.subscribe(eq(sixtyFour), eq(PROJECT_ID))).thenReturn(new SseEmitter());

        controller.aiProgress(PROJECT_ID, eight, principal);
        controller.aiProgress(PROJECT_ID, sixtyFour, principal);

        verify(progressRegistry).subscribe(eight, PROJECT_ID);
        verify(progressRegistry).subscribe(sixtyFour, PROJECT_ID);
    }
}
