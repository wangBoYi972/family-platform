package com.familyhome.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.ai.config.AiConfigStore;
import com.familyhome.ai.config.AiProperties;
import com.familyhome.ai.controller.AiAdminController;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * 管理端配置接口：只回尾号、空 key 保原值、非管理员 403、落盘后重启仍生效。
 *
 * <p>这里刻意不测「key 有没有写进日志」——日志格式随版本变，测试跟着断言日志只会变成噪音；
 * 真正要守住的是**响应体里不出现整串 key**，那一条在下面断言。
 */
class AiAdminControllerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("保存后接口只回尾号，整串 key 不出现在响应里")
    void responseNeverEchoesFullKey() throws Exception {
        AiAdminController controller = controller();
        String key = "sk-ws-secret-value-1234";

        var response = controller.update(asAdmin(), new com.familyhome.ai.dto.AiSettingsDTO(true, key));
        var result = response.data();
        String body = json.writeValueAsString(response);

        assertThat(result.enabled()).isTrue();
        assertThat(result.ready()).isTrue();
        assertThat(result.maskedKey()).isEqualTo("sk-****1234");
        assertThat(body).doesNotContain(key);
    }

    @Test
    @DisplayName("留空 key 只改开关：已保存的 key 不被清掉")
    void blankKeyKeepsExisting() throws Exception {
        AiAdminController controller = controller();
        controller.update(asAdmin(), new com.familyhome.ai.dto.AiSettingsDTO(true, "sk-ws-keep-me-9999"));

        var result = controller.update(asAdmin(), new com.familyhome.ai.dto.AiSettingsDTO(true, "")).data();

        assertThat(result.ready()).isTrue();
        assertThat(result.maskedKey()).isEqualTo("sk-****9999");
    }

    @Test
    @DisplayName("关掉开关后 ready=false，key 仍在：再打开不用重新粘贴")
    void disablingKeepsKey() throws Exception {
        AiAdminController controller = controller();
        controller.update(asAdmin(), new com.familyhome.ai.dto.AiSettingsDTO(true, "sk-ws-toggle-8888"));

        var off = controller.update(asAdmin(), new com.familyhome.ai.dto.AiSettingsDTO(false, "")).data();
        assertThat(off.enabled()).isFalse();
        assertThat(off.ready()).isFalse();
        assertThat(off.maskedKey()).isEqualTo("sk-****8888");
    }

    @Test
    @DisplayName("非管理员读配置 403")
    void nonAdminForbidden() throws Exception {
        AiAdminController controller = controller();
        assertThatThrownBy(() -> controller.get(asMember()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("仅管理员");
    }

    @Test
    @DisplayName("配置落盘后重新构造 store 仍读得到：重启不丢设置")
    void savedSettingsSurviveRestart() throws Exception {
        Path file = tempConfig();
        AiProperties properties = properties(file, "sk-env-key");
        AiConfigStore first = open(properties);
        first.save(true, "sk-ws-persisted-7777");

        AiConfigStore reopened = open(properties);

        assertThat(reopened.effective().usable()).isTrue();
        assertThat(AiConfigStore.mask(reopened.effective().apiKey())).isEqualTo("sk-****7777");
        assertThat(reopened.effective().source()).isEqualTo("admin");
    }

    @Test
    @DisplayName("没有管理端设置时退回环境变量，来源标为 env")
    void fallsBackToEnvironment() throws Exception {
        AiProperties properties = properties(tempConfig(), "sk-env-key");

        AiConfigStore.Effective effective = open(properties).effective();

        assertThat(effective.source()).isEqualTo("env");
        assertThat(effective.usable()).isTrue();
    }

    // ---- 脚手架 ----

    private AiAdminController controller() throws Exception {
        Path file = tempConfig();
        AiProperties properties = properties(file, "");
        AiAdminController controller = new AiAdminController();
        setField(controller, "config", open(properties));
        setField(controller, "properties", properties);
        return controller;
    }

    /** 临时配置文件路径：每个用例独立，避免互相污染，也不碰开发者本机保存的设置。 */
    private Path tempConfig() throws Exception {
        Path file = Files.createTempFile("ai-admin-test", ".json");
        Files.deleteIfExists(file);
        return file;
    }

    /** 手动 new 不会走 @PostConstruct，要自己 init，否则落盘路径是空的。 */
    private AiConfigStore open(AiProperties properties) {
        AiConfigStore store = new AiConfigStore(json, properties);
        store.init();
        return store;
    }

    private AiProperties properties(Path configFile, String envKey) {
        return new AiProperties(true, envKey, "http://model", "qwen-plus", 3, "qwen3-asr-flash",
                "http://content", "http://file", "http://archive", "http://user", configFile.toString());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static HttpServletRequest asAdmin() {
        return session(new SessionPrincipal(1, 10, "ADMIN", "s", "admin"));
    }

    private static HttpServletRequest asMember() {
        return session(new SessionPrincipal(2, 10, "MEMBER", "s", "admin"));
    }

    private static HttpServletRequest session(SessionPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionPrincipal.class.getName(), principal);
        return request;
    }
}
