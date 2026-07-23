package com.codeatlas.server.service;

import com.codeatlas.server.entity.User;

import java.util.Map;

public interface UserService {

    Map<String, Object> register(String username, String password, String email);

    Map<String, Object> login(String username, String password);

    User getCurrentUser(Long userId);

    void changePassword(Long userId, String oldPassword, String newPassword);
}
