package com.familyhome.ai.controller;

import com.familyhome.ai.config.AiConfigStore;
import com.familyhome.ai.config.AiProperties;
import com.familyhome.ai.dto.AiSettingsDTO;
import com.familyhome.ai.dto.AiSettingsVO;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 助手配置的管理端入口：管理员在这里填模型服务 key 与开关。
 *
 * <p>只给管理员。读取接口**永远只回尾号**——管理员在页面上需要认出「用的是哪把 key」，
 * 但整串 key 一旦进了响应体，就会出现在浏览器缓存、开发者工具和任何抓包记录里。
 *
 * <p>环境变量仍然优先于缺省值；这里保存的设置会覆盖环境变量，方便本地和临时环境
 * 不重启、不改环境变量就能换 key。
 */
@RestController
@RequestMapping("/api/ai/settings")
public class AiAdminController {

    @Autowired
    private AiConfigStore config;

    @Autowired
    private AiProperties properties;

    @GetMapping
    public ApiResponse<AiSettingsVO> get(HttpServletRequest request) {
        SessionPrincipal.from(request).requireAdmin();
        return ApiResponse.success(describe());
    }

    @PutMapping
    public ApiResponse<AiSettingsVO> update(HttpServletRequest request, @Valid @RequestBody AiSettingsDTO input) {
        SessionPrincipal.from(request).requireAdmin();
        config.save(input.enabled(), input.apiKey());
        return ApiResponse.success(describe());
    }

    private AiSettingsVO describe() {
        AiConfigStore.Effective settings = config.effective();
        return new AiSettingsVO(
                settings.enabled(),
                settings.usable(),
                AiConfigStore.mask(settings.apiKey()),
                settings.source(),
                properties.model(),
                properties.baseUrl());
    }
}
