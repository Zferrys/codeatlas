package com.codeatlas.server.config;

import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动自愈：进程被 kill（SIGTERM/SIGKILL）时 RUNNING 扫描无法走完失败分支，
 * 残留状态会锁死该项目再次扫描（triggerScan 见 RUNNING 即拒绝），
 * 半成品 class_summary/violation 也会污染"最新扫描"数据口径。
 * 启动时标记残留 RUNNING 为 FAILED 并清理半成品，语义与运行期失败路径一致。
 * 用最高优先级 runner 而非 ApplicationReadyEvent：必须先于 WorkspaceCleanupService
 * （同为 runner）执行，使其随后立即回收中断扫描的工作目录，而非当作活跃目录保留。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScanStateRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanStateRecovery.class);

    private final ScanMapper scanMapper;
    private final ClassSummaryMapper classSummaryMapper;
    private final ViolationMapper violationMapper;

    public ScanStateRecovery(ScanMapper scanMapper, ClassSummaryMapper classSummaryMapper,
                             ViolationMapper violationMapper) {
        this.scanMapper = scanMapper;
        this.classSummaryMapper = classSummaryMapper;
        this.violationMapper = violationMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ScanRecord> runningScans;
        try {
            runningScans = scanMapper.findRunningScans();
        } catch (Exception e) {
            log.error("Scan state recovery failed to query RUNNING scans: {}", e.getMessage());
            return;
        }
        if (runningScans.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (ScanRecord scan : runningScans) {
            try {
                classSummaryMapper.deleteByScanId(scan.getId());
                violationMapper.deleteByScanId(scan.getId());
                scan.setStatus("FAILED");
                scan.setErrorMessage("应用重启导致扫描中断");
                scan.setCompletedAt(LocalDateTime.now());
                scanMapper.updateStats(scan);
                recovered++;
            } catch (Exception e) {
                log.warn("Failed to recover stale scan id={}: {}", scan.getId(), e.getMessage());
            }
        }
        log.warn("Recovered {} stale RUNNING scan(s) to FAILED on startup", recovered);
    }
}
