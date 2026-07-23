package com.codeatlas.server.service;

import java.util.Map;

public interface SearchService {

    Map<String, Object> search(String keyword, String type, Long userId);
}
