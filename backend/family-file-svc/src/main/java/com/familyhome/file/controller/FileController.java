package com.familyhome.file.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.file.dto.FileUploadDTO;
import com.familyhome.file.service.FileService;
import com.familyhome.file.vo.FileVO;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/files")
public class FileController {
    @Autowired
    private FileService service;

    @GetMapping
    public ApiResponse<List<FileVO>> list(HttpServletRequest r) {
        return ApiResponse.success(service.list(SessionPrincipal.from(r)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FileVO> upload(
            HttpServletRequest r, @Valid @ModelAttribute FileUploadDTO input) throws IOException {
        return ApiResponse.success(service.upload(SessionPrincipal.from(r), input));
    }

    @GetMapping("/{id}/metadata")
    public ApiResponse<FileVO> metadata(HttpServletRequest r, @PathVariable String id) {
        return ApiResponse.success(service.metadata(SessionPrincipal.from(r), id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(HttpServletRequest r, @PathVariable String id) {
        var result = service.download(SessionPrincipal.from(r), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store")
                .header(
                        "Content-Disposition",
                        ContentDisposition.attachment()
                                .filename(result.name(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(result.resource());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest r, @PathVariable String id) {
        service.delete(SessionPrincipal.from(r), id);
        return ApiResponse.success(null);
    }
}
