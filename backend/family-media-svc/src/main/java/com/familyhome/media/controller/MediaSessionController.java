package com.familyhome.media.controller;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.media.dto.MediaSessionCreateDTO;
import com.familyhome.media.service.MediaSessionService;
import com.familyhome.media.vo.MediaSessionVO;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/media")
public class MediaSessionController {
    @Autowired private MediaSessionService service;

    @GetMapping("/sessions")
    public ApiResponse<List<MediaSessionVO>> list(HttpServletRequest r) {
        return ApiResponse.success(service.list(SessionPrincipal.from(r)));
    }

    @PostMapping("/sessions")
    public ApiResponse<MediaSessionVO> create(
            HttpServletRequest r, @Valid @RequestBody MediaSessionCreateDTO i) {
        return ApiResponse.success(service.create(SessionPrincipal.from(r), i));
    }

    @PostMapping("/sessions/{id}/close")
    public ApiResponse<Void> close(HttpServletRequest r, @PathVariable String id) {
        service.close(SessionPrincipal.from(r), id);
        return ApiResponse.success(null);
    }
}
