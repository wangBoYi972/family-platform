package com.familyhome.content.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.common.api.PageQuery;
import com.familyhome.content.dto.EntrySaveDTO;
import com.familyhome.content.service.EntryService;
import com.familyhome.content.vo.EntryVO;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/{kind:notices|calendar|dynamics|albums}")
public class EntryController {
    @Autowired
    private EntryService service;

    @GetMapping
    public ApiResponse<List<EntryVO>> list(
            HttpServletRequest r,
            @PathVariable String kind,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(
                service.list(SessionPrincipal.from(r), kind, PageQuery.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EntryVO> create(
            HttpServletRequest r, @PathVariable String kind, @Valid @RequestBody EntrySaveDTO i) {
        return ApiResponse.success(
                service.create(SessionPrincipal.from(r), r.getHeader("Authorization"), kind, i));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            HttpServletRequest r,
            @PathVariable String kind,
            @PathVariable long id,
            @Valid @RequestBody EntrySaveDTO i) {
        service.update(SessionPrincipal.from(r), r.getHeader("Authorization"), kind, id, i);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            HttpServletRequest r, @PathVariable String kind, @PathVariable long id) {
        service.delete(SessionPrincipal.from(r), kind, id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/like")
    public ApiResponse<Void> like(
            HttpServletRequest r, @PathVariable String kind, @PathVariable long id) {
        service.like(SessionPrincipal.from(r), kind, id);
        return ApiResponse.success(null);
    }
}
