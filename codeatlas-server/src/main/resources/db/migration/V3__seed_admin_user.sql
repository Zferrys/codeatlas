-- ============================================================
-- 预置管理员账号
-- 用户名: admin   密码: admin123
-- ============================================================
INSERT IGNORE INTO `user` (`username`, `password_hash`, `email`, `role`, `status`)
VALUES ('admin',
        '$2a$10$JTChg8TXIwTzIH3QmgDhNOh47tUfJ/POOXiMaTivd5/Tg9zpopbfG',
        'admin@codeatlas.com',
        'ADMIN',
        1);
