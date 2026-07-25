ALTER TABLE project ADD COLUMN last_accessed_at DATETIME DEFAULT NULL COMMENT '最后访问时间';
CREATE INDEX idx_project_last_accessed ON project(last_accessed_at);
