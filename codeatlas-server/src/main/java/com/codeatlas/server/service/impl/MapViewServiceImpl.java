package com.codeatlas.server.service.impl;

import com.codeatlas.server.entity.MapViewSnapshot;
import com.codeatlas.server.mapper.MapViewSnapshotMapper;
import com.codeatlas.server.service.MapViewService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MapViewServiceImpl implements MapViewService {

    private final MapViewSnapshotMapper mapper;

    public MapViewServiceImpl(MapViewSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<String, Object> saveView(Long projectId, Long userId, String name, boolean isPublic, String viewState) {
        if (viewState == null) {
            throw new IllegalArgumentException("viewState 不能为空");
        }
        MapViewSnapshot snapshot = new MapViewSnapshot();
        snapshot.setProjectId(projectId);
        snapshot.setUserId(userId);
        snapshot.setName(name != null ? name : "未命名视图");
        snapshot.setIsPublic(isPublic ? 1 : 0);
        snapshot.setViewState(viewState);
        mapper.insert(snapshot);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", snapshot.getId());
        result.put("name", snapshot.getName());
        result.put("createdAt", snapshot.getCreatedAt());
        return result;
    }

    @Override
    public List<Map<String, Object>> listViews(Long projectId, Long userId) {
        List<MapViewSnapshot> snapshots = mapper.findByProjectId(projectId, userId);
        List<Map<String, Object>> records = new ArrayList<>();
        for (MapViewSnapshot s : snapshots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("name", s.getName());
            item.put("userId", s.getUserId());
            item.put("isPublic", s.getIsPublic());
            item.put("updatedAt", s.getUpdatedAt());
            records.add(item);
        }
        return records;
    }

    @Override
    public void deleteView(Long viewId, Long userId) {
        int affected = mapper.deleteByIdAndUser(viewId, userId);
        if (affected == 0) {
            throw new IllegalArgumentException("视图不存在或无权删除");
        }
    }
}
