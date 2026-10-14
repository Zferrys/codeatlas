package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileController 单元测试")
class FileControllerTest {

    private static final Long USER_ID = 110L;

    @Mock
    private FileService fileService;
    @Mock
    private MultipartFile file;

    private FileController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new FileController(fileService);
        principal = new CodeAtlasUserDetails(USER_ID, "nina", "DEVELOPER");
    }

    @Test
    @DisplayName("upload — 文件与用户 id 透传，返回创建的项目")
    void uploadPassesFileAndUser() {
        ProjectVO vo = new ProjectVO();
        vo.setId(200L);
        vo.setName("uploaded");
        when(fileService.uploadAndScan(file, USER_ID)).thenReturn(vo);

        ApiResponse<ProjectVO> response = controller.upload(file, principal);

        assertEquals(200, response.getCode());
        assertSame(vo, response.getData());
        assertEquals(200L, response.getData().getId());
        verify(fileService).uploadAndScan(file, USER_ID);
    }
}
