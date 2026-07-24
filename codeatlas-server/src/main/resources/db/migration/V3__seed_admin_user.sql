-- ============================================================
-- 预置管理员账号
-- 重要：首次部署后请立即修改管理员密码，切勿使用默认密码运行生产环境
-- ============================================================
INSERT IGNORE INTO `user` (`username`, `password_hash`, `email`, `role`, `status`)
VALUES ('zferry',
        '$2b$10$SN4HlhhcPMkBzzf6nkkv6.H7hKOG6IEiOfFjCqiYO3yRAwPHUX6lG',
        'zferry@codeatlas.com',
        'ADMIN',
        1);
