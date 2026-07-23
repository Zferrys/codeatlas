package com.codeatlas.server.service;

import com.codeatlas.server.dto.response.ProjectVO;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    /**
     * 上传 ZIP/TAR.GZ 源码包，自动创建项目并触发扫描。
     */
    ProjectVO uploadAndScan(MultipartFile file, Long userId);
}
