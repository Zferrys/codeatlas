package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.MapViewSnapshot;
import com.codeatlas.server.mapper.MapViewSnapshotMapper;
import com.codeatlas.server.service.impl.MapViewServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MapViewServiceImpl 单元测试")
class MapViewServiceImplTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;

    @Mock
    private MapViewSnapshotMapper mapper;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    @InjectMocks
    private MapViewServiceImpl mapViewService;

    private MapViewSnapshot snapshot(Long id, String name, boolean isPublic) {
        MapViewSnapshot s = new MapViewSnapshot();
        s.setId(id);
        s.setProjectId(PROJECT_ID);
        s.setUserId(USER_ID);
        s.setName(name);
        s.setIsPublic(isPublic ? 1 : 0);
        s.setViewState("{\"zoom\":1.5}");
        s.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        s.setUpdatedAt(LocalDateTime.of(2026, 1, 3, 4, 5, 6));
        return s;
    }

    @Test
    @DisplayName("保存视图：viewState 为空时先抛 400，且不进行鉴权")
    void saveView_nullViewState_shouldThrow400BeforeAccessCheck() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> mapViewService.saveView(PROJECT_ID, USER_ID, "视图A", true, null));

        assertEquals(400, ex.getCode());
        assertEquals("viewState 不能为空", ex.getMessage());
        verifyNoInteractions(projectAccessChecker);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("保存视图：鉴权后落库，isPublic=true 写入 1，返回 id/name/createdAt")
    void saveView_withAllFields_shouldInsertAndReturnIdNameCreatedAt() {
        doAnswer(invocation -> {
            MapViewSnapshot s = invocation.getArgument(0);
            s.setId(99L);
            return 1;
        }).when(mapper).insert(any(MapViewSnapshot.class));

        Map<String, Object> result = mapViewService.saveView(
                PROJECT_ID, USER_ID, "我的视图", true, "{\"zoom\":2}");

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        ArgumentCaptor<MapViewSnapshot> captor = ArgumentCaptor.forClass(MapViewSnapshot.class);
        verify(mapper).insert(captor.capture());
        MapViewSnapshot inserted = captor.getValue();
        assertEquals(PROJECT_ID, inserted.getProjectId());
        assertEquals(USER_ID, inserted.getUserId());
        assertEquals("我的视图", inserted.getName());
        assertEquals(1, inserted.getIsPublic());
        assertEquals("{\"zoom\":2}", inserted.getViewState());

        assertEquals(99L, result.get("id"));
        assertEquals("我的视图", result.get("name"));
        assertNull(result.get("createdAt"));
        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("保存视图：name 为空取默认值，isPublic=false 写入 0")
    void saveView_nullName_shouldUseDefaultNameAndPrivateFlag() {
        mapViewService.saveView(PROJECT_ID, USER_ID, null, false, "{}");

        ArgumentCaptor<MapViewSnapshot> captor = ArgumentCaptor.forClass(MapViewSnapshot.class);
        verify(mapper).insert(captor.capture());
        assertEquals("未命名视图", captor.getValue().getName());
        assertEquals(0, captor.getValue().getIsPublic());
    }

    @Test
    @DisplayName("视图列表：逐条映射 5 个字段")
    void listViews_shouldMapFields() {
        when(mapper.findByProjectId(PROJECT_ID, USER_ID))
                .thenReturn(Arrays.asList(snapshot(1L, "视图1", true), snapshot(2L, "视图2", false)));

        List<Map<String, Object>> result = mapViewService.listViews(PROJECT_ID, USER_ID);

        assertEquals(2, result.size());
        Map<String, Object> first = result.get(0);
        assertEquals(1L, first.get("id"));
        assertEquals("视图1", first.get("name"));
        assertEquals(USER_ID, first.get("userId"));
        assertEquals(1, first.get("isPublic"));
        assertEquals(LocalDateTime.of(2026, 1, 3, 4, 5, 6), first.get("updatedAt"));
        assertEquals(5, first.size());

        assertEquals(2L, result.get(1).get("id"));
        assertEquals(0, result.get(1).get("isPublic"));
    }

    @Test
    @DisplayName("视图列表：无数据返回空列表")
    void listViews_empty_shouldReturnEmptyList() {
        when(mapper.findByProjectId(PROJECT_ID, USER_ID)).thenReturn(List.of());

        List<Map<String, Object>> result = mapViewService.listViews(PROJECT_ID, USER_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("删除视图：影响行数为 0 抛 404")
    void deleteView_affectedZero_shouldThrow404() {
        when(mapper.deleteByIdAndUser(5L, USER_ID)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> mapViewService.deleteView(5L, USER_ID));

        assertEquals(404, ex.getCode());
        assertEquals("视图不存在或无权删除", ex.getMessage());
    }

    @Test
    @DisplayName("删除视图：删除成功不抛异常")
    void deleteView_success_shouldNotThrow() {
        when(mapper.deleteByIdAndUser(5L, USER_ID)).thenReturn(1);

        assertDoesNotThrow(() -> mapViewService.deleteView(5L, USER_ID));

        verify(mapper).deleteByIdAndUser(eq(5L), eq(USER_ID));
        verifyNoMoreInteractions(mapper);
    }
}
