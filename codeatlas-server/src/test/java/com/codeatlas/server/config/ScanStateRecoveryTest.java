package com.codeatlas.server.config;

import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanStateRecovery 启动自愈测试")
class ScanStateRecoveryTest {

    @Mock
    private ScanMapper scanMapper;

    @Mock
    private ClassSummaryMapper classSummaryMapper;

    @Mock
    private ViolationMapper violationMapper;

    private ScanStateRecovery recovery;
    private final ApplicationArguments args = mock(ApplicationArguments.class);

    @BeforeEach
    void setUp() {
        recovery = new ScanStateRecovery(scanMapper, classSummaryMapper, violationMapper);
    }

    private static ScanRecord runningScan(long id) {
        ScanRecord scan = new ScanRecord();
        scan.setId(id);
        scan.setProjectId(9L);
        scan.setStatus("RUNNING");
        return scan;
    }

    @Test
    @DisplayName("查询 RUNNING 扫描失败：记录错误后直接返回，不做任何清理")
    void run_queryFailure_shouldAbortWithoutCleanup() {
        when(scanMapper.findRunningScans()).thenThrow(new RuntimeException("db down"));

        recovery.run(args);

        verifyNoInteractions(classSummaryMapper, violationMapper);
        verify(scanMapper, never()).updateStats(any());
    }

    @Test
    @DisplayName("无残留 RUNNING 扫描：直接返回，不触碰清理与更新")
    void run_noRunningScans_shouldDoNothing() {
        when(scanMapper.findRunningScans()).thenReturn(Collections.emptyList());

        recovery.run(args);

        verifyNoInteractions(classSummaryMapper, violationMapper);
        verify(scanMapper, never()).updateStats(any());
    }

    @Test
    @DisplayName("残留扫描：清理半成品数据并标记 FAILED 和中断原因")
    void run_staleScan_shouldCleanAndMarkFailed() {
        ScanRecord scan = runningScan(100L);
        when(scanMapper.findRunningScans()).thenReturn(Collections.singletonList(scan));

        recovery.run(args);

        verify(classSummaryMapper).deleteByScanId(100L);
        verify(violationMapper).deleteByScanId(100L);

        ArgumentCaptor<ScanRecord> captor = ArgumentCaptor.forClass(ScanRecord.class);
        verify(scanMapper).updateStats(captor.capture());
        ScanRecord updated = captor.getValue();
        assertSame(scan, updated, "应在原对象上修改状态后交给 updateStats");
        assertEquals("FAILED", updated.getStatus());
        assertEquals("应用重启导致扫描中断", updated.getErrorMessage());
        assertNotNull(updated.getCompletedAt());
    }

    @Test
    @DisplayName("多条残留扫描：逐条完成清理与状态更新")
    void run_multipleStaleScans_shouldRecoverEach() {
        ScanRecord first = runningScan(11L);
        ScanRecord second = runningScan(22L);
        when(scanMapper.findRunningScans()).thenReturn(Arrays.asList(first, second));

        recovery.run(args);

        verify(classSummaryMapper).deleteByScanId(11L);
        verify(classSummaryMapper).deleteByScanId(22L);
        verify(violationMapper).deleteByScanId(11L);
        verify(violationMapper).deleteByScanId(22L);
        verify(scanMapper, times(2)).updateStats(any(ScanRecord.class));
        assertEquals("FAILED", first.getStatus());
        assertEquals("FAILED", second.getStatus());
    }

    @Test
    @DisplayName("单条恢复失败（清理抛异常）：跳过该条继续恢复后续扫描")
    void run_oneScanFails_shouldContinueWithRemaining() {
        ScanRecord broken = runningScan(1L);
        ScanRecord healthy = runningScan(2L);
        when(scanMapper.findRunningScans()).thenReturn(Arrays.asList(broken, healthy));
        when(classSummaryMapper.deleteByScanId(1L)).thenThrow(new RuntimeException("fk constraint"));

        recovery.run(args);

        assertEquals("RUNNING", broken.getStatus(), "失败项不应被修改为 FAILED");
        verify(scanMapper, never()).updateStats(broken);
        verify(violationMapper, never()).deleteByScanId(1L);

        verify(classSummaryMapper).deleteByScanId(2L);
        verify(violationMapper).deleteByScanId(2L);
        verify(scanMapper).updateStats(healthy);
        assertEquals("FAILED", healthy.getStatus());
    }
}
