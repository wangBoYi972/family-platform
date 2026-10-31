package com.familyhome.archive.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.archive.dto.ArchiveSaveDTO;
import com.familyhome.archive.service.ArchiveService;
import com.familyhome.archive.vo.*;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.common.api.PageQuery;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/archives")
public class ArchiveController {
    @Autowired
    private ArchiveService service;

    @GetMapping
    public ApiResponse<List<ArchiveVO>> list(
            HttpServletRequest r,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        return ApiResponse.success(
                service.list(SessionPrincipal.from(r), PageQuery.of(page, size), keyword, category));
    }

    @GetMapping("/{id}")
    public ApiResponse<ArchiveDetailVO> detail(HttpServletRequest r, @PathVariable String id) {
        return ApiResponse.success(service.detail(SessionPrincipal.from(r), id));
    }

    @PostMapping
    public ApiResponse<ArchiveVO> create(HttpServletRequest r, @Valid @RequestBody ArchiveSaveDTO i) {
        return ApiResponse.success(service.create(SessionPrincipal.from(r), i));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            HttpServletRequest r, @PathVariable String id, @Valid @RequestBody ArchiveSaveDTO i) {
        service.update(SessionPrincipal.from(r), id, i);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest r, @PathVariable String id) {
        service.delete(SessionPrincipal.from(r), id);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<List<ArchiveAuditVO>> audit(
            HttpServletRequest r,
            @PathVariable String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(
                service.audit(SessionPrincipal.from(r), id, PageQuery.of(page, size, 100)));
    }
}
