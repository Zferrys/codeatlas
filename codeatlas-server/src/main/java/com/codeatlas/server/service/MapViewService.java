package com.codeatlas.server.service;

import java.util.List;
import java.util.Map;

public interface MapViewService {

    Map<String, Object> saveView(Long projectId, Long userId, String name, boolean isPublic, String viewState);

    List<Map<String, Object>> listViews(Long projectId, Long userId);

    void deleteView(Long viewId, Long userId);
}
