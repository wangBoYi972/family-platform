package com.familyhome.content.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.common.api.PageQuery;
import com.familyhome.content.dto.TaskCreateDTO;
import com.familyhome.content.dto.TaskStatusDTO;
import com.familyhome.content.dto.TaskUpdateDTO;
import com.familyhome.content.service.TaskService;
import com.familyhome.content.vo.TaskStatsVO;
import com.familyhome.content.vo.TaskVO;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/tasks")
public class TaskController {
    @Autowired
    private TaskService service;

    @GetMapping
    public ApiResponse<List<TaskVO>> list(
            HttpServletRequest r,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        // 不传分页/筛选参数时等价于原来的「取前 500 条」，老客户端行为不变
        return ApiResponse.success(
                service.list(SessionPrincipal.from(r), PageQuery.of(page, size), keyword, status));
    }

    @GetMapping("/stats")
    public ApiResponse<TaskStatsVO> stats(HttpServletRequest r) {
        return ApiResponse.success(service.stats(SessionPrincipal.from(r)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskVO> create(HttpServletRequest r, @Valid @RequestBody TaskCreateDTO i) {
        return ApiResponse.success(
                service.create(SessionPrincipal.from(r), r.getHeader("Authorization"), i));
    }

    @PutMapping("/{id}")
    public ApiResponse<TaskVO> update(
            HttpServletRequest r,
            @PathVariable long id,
            @Valid @RequestBody TaskUpdateDTO i) {
        return ApiResponse.success(
                service.update(SessionPrincipal.from(r), r.getHeader("Authorization"), id, i));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<Void> status(
            HttpServletRequest r, @PathVariable long id, @Valid @RequestBody TaskStatusDTO i) {
        service.status(SessionPrincipal.from(r), id, i.status());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest r, @PathVariable long id) {
        service.delete(SessionPrincipal.from(r), id);
        return ApiResponse.success(null);
    }
}
