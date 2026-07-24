package com.codeatlas.server.service;

import java.util.List;
import java.util.Map;

public interface ClassService {

    Map<String, Object> getClassDetail(Long projectId, String fqn);

    List<Map<String, Object>> searchClasses(Long projectId, String keyword, int limit);
}
