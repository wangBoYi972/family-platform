package com.familyhome.media.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 通话参数与 TURN 配置，全部可用环境变量覆盖，默认值面向本地开发。 */
@Component
@ConfigurationProperties(prefix = "family.media")
@Data
public class MediaProperties {
    /** 单个通话的最大人数（含发起者）。P2P 网状下每端编码路数 = 人数 - 1，8 人是音视频可用上限。 */
    private int maxParticipants = 8;

    /** 无人接听的超时秒数，超时后会话置为 NO_ANSWER。 */
    private int ringTimeoutSeconds = 45;

    /** 断线后保留通话的宽限秒数，避免地铁过隧道就掉线。 */
    private int reconnectGraceSeconds = 30;

    /** 是否通过用户服务校验目标成员属于同一家庭；仅测试环境可关闭。 */
    private boolean verifyMembers = true;

    private Turn turn = new Turn();

    @Data
    public static class Turn {
        /** coturn static-auth-secret，留空则只下发 STUN（局域网/公网直连可用）。 */
        private String secret;

        /** 凭据有效期，coturn 侧校验 username 中的过期时间。 */
        private int ttlSeconds = 3600;

        private List<String> urls = new ArrayList<>();

        private List<String> stunUrls = new ArrayList<>(List.of("stun:stun.l.google.com:19302"));
    }
}
