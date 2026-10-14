package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.mapper.InsightMapper;
import com.codeatlas.server.service.impl.InsightServiceImpl;
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
@DisplayName("InsightServiceImpl 单元测试")
class InsightServiceImplTest {

    private static final Long PROJECT_ID = 1L;

    @Mock
    private InsightMapper insightMapper;

    @InjectMocks
    private InsightServiceImpl insightService;

    private InsightEntity insight(Long id, String type, String title) {
        InsightEntity e = new InsightEntity();
        e.setId(id);
        e.setProjectId(PROJECT_ID);
        e.setType(type);
        e.setTitle(title);
        e.setContent("content-" + id);
        return e;
    }

    @Test
    @DisplayName("保存洞察：直接委托 mapper.insert")
    void saveInsight_shouldDelegateToInsert() {
        InsightEntity entity = insight(1L, "ARCH_STORY", "故事");

        insightService.saveInsight(entity);

        verify(insightMapper).insert(entity);
        verifyNoMoreInteractions(insightMapper);
    }

    @Test
    @DisplayName("洞察分页：offset=(page-1)*size，返回分页结果")
    void getInsights_shouldQueryWithOffsetAndReturnPage() {
        List<InsightEntity> records = List.of(insight(1L, "ARCH_STORY", "A"));
        when(insightMapper.countByProjectId(PROJECT_ID)).thenReturn(11L);
        when(insightMapper.findByProjectIdPaged(PROJECT_ID, 10, 5)).thenReturn(records);

        PageResult<InsightEntity> result = insightService.getInsights(PROJECT_ID, 3, 5);

        assertEquals(11L, result.getTotal());
        assertSame(records, result.getRecords());
        assertEquals(3, result.getPage());
        assertEquals(5, result.getSize());
    }

    @Test
    @DisplayName("按类型分页：类型参数透传，offset=(page-1)*size")
    void getInsightsByType_shouldPassTypeAndOffset() {
        List<InsightEntity> records = List.of(insight(2L, "ANTI_PATTERN", "B"));
        when(insightMapper.countByProjectIdAndType(PROJECT_ID, "ANTI_PATTERN")).thenReturn(7L);
        when(insightMapper.findByProjectIdAndTypePaged(PROJECT_ID, "ANTI_PATTERN", 0, 20))
                .thenReturn(records);

        PageResult<InsightEntity> result =
                insightService.getInsightsByType(PROJECT_ID, "ANTI_PATTERN", 1, 20);

        assertEquals(7L, result.getTotal());
        assertSame(records, result.getRecords());
        assertEquals(1, result.getPage());
        assertEquals(20, result.getSize());
    }

    @Test
    @DisplayName("按类型分页第 2 页：offset = (2-1)*10 = 10 精确透传（乘法而非除法）")
    void getInsightsByType_pageTwo_shouldUseExactOffsetTen() {
        insightService.getInsightsByType(PROJECT_ID, "ANTI_PATTERN", 2, 10);

        verify(insightMapper).findByProjectIdAndTypePaged(PROJECT_ID, "ANTI_PATTERN", 10, 10);
    }

    @Test
    @DisplayName("最新架构故事：无记录返回 null")
    void getLatestArchStory_empty_shouldReturnNull() {
        when(insightMapper.findLatestByProjectIdAndType(PROJECT_ID, "ARCH_STORY"))
                .thenReturn(List.of());

        assertNull(insightService.getLatestArchStory(PROJECT_ID));
    }

    @Test
    @DisplayName("最新架构故事：取列表第一个元素")
    void getLatestArchStory_present_shouldReturnFirst() {
        InsightEntity first = insight(1L, "ARCH_STORY", "最新");
        InsightEntity second = insight(2L, "ARCH_STORY", "更旧");
        when(insightMapper.findLatestByProjectIdAndType(PROJECT_ID, "ARCH_STORY"))
                .thenReturn(List.of(first, second));

        InsightEntity result = insightService.getLatestArchStory(PROJECT_ID);

        assertSame(first, result);
        verify(insightMapper).findLatestByProjectIdAndType(PROJECT_ID, "ARCH_STORY");
    }

    @Test
    @DisplayName("最新按类型：透传自定义类型并返回首个")
    void getLatestByType_shouldUseGivenTypeAndReturnFirst() {
        InsightEntity first = insight(5L, "CONSTITUTION_CHECK", "合规");
        when(insightMapper.findLatestByProjectIdAndType(PROJECT_ID, "CONSTITUTION_CHECK"))
                .thenReturn(List.of(first));

        InsightEntity result = insightService.getLatestByType(PROJECT_ID, "CONSTITUTION_CHECK");

        assertSame(first, result);
    }

    @Test
    @DisplayName("最新按类型：无记录返回 null")
    void getLatestByType_empty_shouldReturnNull() {
        when(insightMapper.findLatestByProjectIdAndType(PROJECT_ID, "CUSTOM"))
                .thenReturn(List.of());

        assertNull(insightService.getLatestByType(PROJECT_ID, "CUSTOM"));
    }
}
