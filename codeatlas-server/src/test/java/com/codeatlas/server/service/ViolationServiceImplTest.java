package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.entity.ViolationEntity;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import com.codeatlas.server.service.impl.ViolationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ViolationServiceImpl 单元测试")
class ViolationServiceImplTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;

    @Mock
    private ViolationMapper mapper;
    @Mock
    private ScanMapper scanMapper;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    @InjectMocks
    private ViolationServiceImpl violationService;

    private ViolationEntity violation(Long id) {
        ViolationEntity v = new ViolationEntity();
        v.setId(id);
        v.setProjectId(PROJECT_ID);
        v.setSeverity("MAJOR");
        v.setClassFqn("com.demo.AService");
        v.setMessage("message-" + id);
        return v;
    }

    @Test
    @DisplayName("违规分页：先鉴权，再按 offset=(page-1)*size 查询并组装 PageResult")
    void getViolations_shouldAuthThenQueryWithOffset() {
        List<ViolationEntity> records = List.of(violation(1L), violation(2L));
        when(mapper.countByProjectId(PROJECT_ID)).thenReturn(42L);
        when(mapper.findByProjectIdPaged(PROJECT_ID, 10, 10)).thenReturn(records);

        PageResult<ViolationEntity> result =
                violationService.getViolations(PROJECT_ID, 2, 10, USER_ID);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        assertEquals(42L, result.getTotal());
        assertSame(records, result.getRecords());
        assertEquals(2, result.getPage());
        assertEquals(10, result.getSize());
    }

    @Test
    @DisplayName("违规分页：第一页 offset 为 0")
    void getViolations_firstPage_shouldUseZeroOffset() {
        when(mapper.countByProjectId(PROJECT_ID)).thenReturn(1L);
        when(mapper.findByProjectIdPaged(PROJECT_ID, 0, 20)).thenReturn(List.of(violation(1L)));

        PageResult<ViolationEntity> result =
                violationService.getViolations(PROJECT_ID, 1, 20, USER_ID);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getPage());
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("按扫描查询：扫描记录不存在抛 404，且不鉴权")
    void getViolationsByScanId_scanMissing_shouldThrow404() {
        when(scanMapper.findById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> violationService.getViolationsByScanId(99L, USER_ID));

        assertEquals(404, ex.getCode());
        assertEquals("扫描记录不存在", ex.getMessage());
        verifyNoInteractions(projectAccessChecker);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("按扫描查询：用扫描记录归属项目鉴权后返回违规列表")
    void getViolationsByScanId_scanFound_shouldCheckScanProjectIdAndReturn() {
        ScanRecord scan = new ScanRecord();
        scan.setId(99L);
        scan.setProjectId(PROJECT_ID);
        when(scanMapper.findById(99L)).thenReturn(scan);
        List<ViolationEntity> records = List.of(violation(1L));
        when(mapper.findByScanId(99L)).thenReturn(records);

        List<ViolationEntity> result = violationService.getViolationsByScanId(99L, USER_ID);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        assertSame(records, result);
    }

    @Test
    @DisplayName("解决违规：影响行数为 0 抛 404")
    void resolveViolation_affectedZero_shouldThrow404() {
        when(mapper.resolve(10L, PROJECT_ID)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> violationService.resolveViolation(PROJECT_ID, 10L, USER_ID));

        assertEquals(404, ex.getCode());
        assertEquals("违规记录不存在", ex.getMessage());
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("解决违规：成功时按 (违规ID, 项目ID) 更新")
    void resolveViolation_success_shouldUpdate() {
        when(mapper.resolve(10L, PROJECT_ID)).thenReturn(1);

        assertDoesNotThrow(() -> violationService.resolveViolation(PROJECT_ID, 10L, USER_ID));

        verify(mapper).resolve(10L, PROJECT_ID);
        verifyNoMoreInteractions(mapper);
    }
}
