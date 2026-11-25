package com.familyhome.media.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LiveKit SFU 配置。feature flag 默认关闭，关闭时系统完全退回原有 WebRTC mesh 链路，
 * 不读取密钥、不签发任何 token。所有字段都可由环境变量覆盖（见 application.yml）。
 */
@Component
@ConfigurationProperties(prefix = "family.livekit")
@Data
public class LiveKitProperties {
    /** 总开关：false=mesh（上限 8 人），true=SFU（上限 20+ 人）。灰度时按环境/账号开启。 */
    private boolean enabled = false;

    /** 与 livekit.yaml 的 keys 中 api_key 一致。 */
    private String apiKey = "";

    /** 与 livekit.yaml 的 keys 中 api_secret 一致；HS256 签名要求至少 32 字节。 */
    private String apiSecret = "";

    /** 浏览器侧连接的 WSS 地址，如 wss://livekit.example.com。必须由 Nginx 终止 TLS。 */
    private String publicUrl = "";

    /** 服务端内部访问地址，供后续 RoomServiceClient 主动删房/录制使用，当前仅保留配置。 */
    private String internalUrl = "http://127.0.0.1:7880";

    /** 房间名前缀。房间名由服务端按 callId 生成，客户端不能自定义。 */
    private String roomPrefix = "family-";

    /** JoinToken 有效期（秒），需覆盖单通通话时长；过期后客户端需重新签发。 */
    private int tokenTtlSeconds = 3600;

    /** SFU 模式人数上限（含发起者），高于 mesh 的 8 人。 */
    private int maxParticipants = 20;

    /** 房间名唯一映射到一通通话，避免不同通话串房。 */
    public String roomName(String callId) {
        return roomPrefix + callId;
    }

    /** 是否具备签发 token 的完整凭据。缺一项都应显式报错，而不是签出一个连不上的 token。 */
    public boolean configured() {
        return !isBlank(apiKey) && !isBlank(apiSecret) && !isBlank(publicUrl);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
