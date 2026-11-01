package com.familyhome.im.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.common.api.PageQuery;
import com.familyhome.im.dto.*;
import com.familyhome.im.service.ImService;
import com.familyhome.im.vo.*;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/im")
public class ImController {
    @Autowired
    private ImService service;

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationVO>> list(
            HttpServletRequest r,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(
                service.list(SessionPrincipal.from(r), PageQuery.of(page, size)));
    }

    @PostMapping("/conversations")
    public ApiResponse<ConversationVO> create(
            HttpServletRequest r, @Valid @RequestBody ConversationCreateDTO i) {
        return ApiResponse.success(
                service.create(SessionPrincipal.from(r), r.getHeader("Authorization"), i));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<ConversationVO> detail(HttpServletRequest r, @PathVariable String id) {
        return ApiResponse.success(service.detail(SessionPrincipal.from(r), id));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<MessageVO>> messages(
            HttpServletRequest r,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "0") long before,
            @RequestParam(defaultValue = "0") int limit) {
        return ApiResponse.success(
                service.messages(SessionPrincipal.from(r), id, after, before, limit));
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<MessageVO> send(
            HttpServletRequest r, @PathVariable String id, @Valid @RequestBody MessageSendDTO i) {
        return ApiResponse.success(
                service.send(SessionPrincipal.from(r), r.getHeader("Authorization"), id, i));
    }

    @PostMapping("/conversations/{id}/read")
    public ApiResponse<Void> read(
            HttpServletRequest r, @PathVariable String id, @Valid @RequestBody MessageReadDTO i) {
        service.read(SessionPrincipal.from(r), id, i);
        return ApiResponse.success(null);
    }

    @GetMapping("/conversations/{id}/read")
    public ApiResponse<List<ReadReceiptVO>> reads(HttpServletRequest r, @PathVariable String id) {
        return ApiResponse.success(service.reads(SessionPrincipal.from(r), id));
    }

    @PostMapping("/ws-ticket")
    public ApiResponse<SocketTicketVO> ticket(HttpServletRequest r) {
        return ApiResponse.success(service.ticket(SessionPrincipal.from(r)));
    }
}
