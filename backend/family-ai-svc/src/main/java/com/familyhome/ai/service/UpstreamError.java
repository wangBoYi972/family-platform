package com.familyhome.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 上游（DashScope 兼容端点）错误体的两个小工具：取错误码、压成一行日志。
 *
 * <p>之所以单独摊出来，是因为「403 access_denied」和「404 model_not_found」必须能被区分：
 * 前者是这把 key 没开通该模型，后者是这个端点根本没有这个名字。两者都曾被笼统地写成
 * 「密钥无效」，把人引向完全错误的排查方向。
 */
final class UpstreamError {

    private UpstreamError() {}

    /** DashScope 放在 error.code，个别网关放在顶层 code；取不到就返回空串。 */
    static String code(ObjectMapper json, String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            JsonNode node = json.readTree(raw);
            String code = node.path("error").path("code").asText("");
            return code.isBlank() ? node.path("code").asText("") : code;
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 日志用：去掉换行并截断，避免把整段 HTML 错误页写进日志。 */
    static String brief(String raw) {
        if (raw == null) return "";
        String flat = raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
