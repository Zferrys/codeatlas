package com.codeatlas.server.controller;

import com.codeatlas.server.event.ScanProgressEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanProgressControllerTest {

    private static ScanProgressEvent event(Long scanId, String stage) {
        return new ScanProgressEvent(1L, scanId, stage, 50, "test");
    }

    @Test
    void nonTerminalSnapshotAlwaysReplays() {
        assertTrue(ScanProgressController.shouldReplay(event(7L, "PARSING"), null));
        assertTrue(ScanProgressController.shouldReplay(event(7L, "PARSING"), 7L));
        assertTrue(ScanProgressController.shouldReplay(event(7L, "PARSING"), 99L));
    }

    @Test
    void terminalSnapshotReplaysForSameScan() {
        assertTrue(ScanProgressController.shouldReplay(event(7L, "FAILED"), 7L));
        assertTrue(ScanProgressController.shouldReplay(event(7L, "COMPLETED"), 7L));
    }

    @Test
    void terminalSnapshotSkippedWithoutScanIdOrForOtherScan() {
        assertFalse(ScanProgressController.shouldReplay(event(7L, "FAILED"), null));
        assertFalse(ScanProgressController.shouldReplay(event(7L, "COMPLETED"), null));
        assertFalse(ScanProgressController.shouldReplay(event(7L, "FAILED"), 99L));
    }
}
