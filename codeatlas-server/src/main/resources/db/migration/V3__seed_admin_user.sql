-- ============================================================
-- 预置管理员账号
-- 重要：首次部署后请立即修改管理员密码，切勿使用默认密码运行生产环境
-- ============================================================
INSERT IGNORE INTO `user` (`username`, `password_hash`, `email`, `role`, `status`)
VALUES ('admin',
        '$2a$10$JTChg8TXIwTzIH3QmgDhNOh47tUfJ/POOXiMaTivd5/Tg9zpopbfG',
        'admin@codeatlas.com',
        'ADMIN',
        1);
