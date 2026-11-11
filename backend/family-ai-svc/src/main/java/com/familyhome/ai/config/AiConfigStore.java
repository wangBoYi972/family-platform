package com.familyhome.ai.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * AI 助手的运行时配置：管理员在管理端填写的 key 与开关。
 *
 * <p>来源优先级：管理端保存的设置 &gt; 环境变量 &gt; 缺省关闭。
 * 环境变量依然有效——生产更推荐用 {@code FAMILY_AI_API_KEY} 注入，管理端设置只是给本地和
 * 临时环境一个「不用重启、不用改环境变量」的入口。
 *
 * <p>落盘位置默认 {@code .local/ai-settings.json}（已被 .gitignore 覆盖），权限尽量收紧到
 * 仅属主可读写。文件里是明文 key，这一点不掩饰：能读这个文件的人本来就能读整个应用配置，
 * 真正的边界是部署机的账号权限，不是应用层再加一层加密。
 */
@Component
public class AiConfigStore {

    private static final Logger log = LoggerFactory.getLogger(AiConfigStore.class);

    private final ObjectMapper json;
    private final AiProperties properties;
    private Path file;

    /** null 表示管理端没保存过，此时退回环境变量。 */
    private volatile Setting stored;

    @Autowired
    public AiConfigStore(ObjectMapper json, AiProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        this.file = Path.of(properties.configFile());
        this.stored = read();
    }

    /** 当前生效的配置：管理端设置 > 环境变量（有效时）> 缺省关闭。 */
    public Effective effective() {
        Setting saved = stored;
        if (saved != null) return new Effective(saved.enabled(), saved.apiKey(), "admin");
        // 环境变量的 key 为空时视同「未配置」，继续降级到缺省关闭，
        // 避免空环境变量把管理端保存的有效配置顶掉（文件存在但 read() 认为 key 为空时 stored 为 null）
        String envKey = properties.apiKey();
        if (envKey != null && !envKey.isBlank())
            return new Effective(properties.enabled(), envKey, "env");
        return new Effective(false, "", "default");
    }

    /**
     * 保存管理端设置。{@code apiKey} 为空表示「只改开关，不动已存的 key」——
     * 管理员关掉再打开时不用重新粘贴一遍。
     */
    public synchronized Effective save(boolean enabled, String apiKey) {
        String key = apiKey == null || apiKey.isBlank() ? effective().apiKey() : apiKey.trim();
        Setting next = new Setting(enabled, key == null ? "" : key);
        if (!write(next)) {
            // 写失败就不改内存态：否则配置「生效了但重启就没了」，排查时毫无线索
            throw new IllegalStateException("配置保存失败，请检查服务端 .local 目录权限");
        }
        stored = next;
        log.info("AI 助手配置已更新：enabled={}，key={}", enabled, mask(key));
        return effective();
    }

    /** 只回显首三尾四：管理员要能认出用的是哪把 key，但整串不该出现在任何响应或日志里。 */
    public static String mask(String key) {
        if (key == null || key.isBlank()) return "";
        return key.length() <= 8 ? "****" : key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    private Setting read() {
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode node = json.readTree(Files.readString(file));
            String key = node.path("apiKey").asText("");
            // 空 key 的文件视同「没配过」：手工建的空文件不该把环境变量顶掉
            if (key.isBlank()) return null;
            return new Setting(node.path("enabled").asBoolean(true), key);
        } catch (IOException e) {
            log.warn("AI 配置读取失败，按未配置处理：{}", e.getMessage());
            return null;
        }
    }

    private boolean write(Setting setting) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            ObjectNode node = json.createObjectNode();
            node.put("enabled", setting.enabled());
            node.put("apiKey", setting.apiKey());
            Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            restrictPermissions();
            return true;
        } catch (IOException e) {
            log.error("AI 配置写入失败", e);
            return false;
        }
    }

    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 没有 POSIX 权限位；兜底是 .local 目录本身不进版本库
        }
    }

    private record Setting(boolean enabled, String apiKey) {}

    /** 一次对话实际要用的配置。{@code source} 让管理端能显示「现在生效的是哪来的」。 */
    public record Effective(boolean enabled, String apiKey, String source) {
        public boolean usable() {
            return enabled && apiKey != null && !apiKey.isBlank();
        }
    }
}
