package com.familyhome.media.service;

import com.familyhome.media.config.LiveKitProperties;
import com.familyhome.media.model.Calls;
import com.familyhome.media.vo.CallParticipantVO;
import com.familyhome.media.vo.CallVO;
import com.familyhome.media.vo.LiveKitTokenVO;
import com.familyhome.security.SessionPrincipal;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 签发 LiveKit 入房凭证。
 *
 * <p>这是 SFU 改造的安全命门：token 一旦签出，持有者就能进入对应房间并收发媒体，
 * 因此必须同时满足三件事——当前登录者确实是这通通话的参与者、通话未结束、SFU 已启用。
 * identity 一律取服务端会话的 userId，绝不信任客户端上报的任何身份字段。
 *
 * <p>LiveKit 的 JoinToken 就是标准 HS256 JWT（claims 中的 video 段是 LiveKit 的 VideoGrant），
 * 因此复用项目已有的 jjwt 签发，无需引入额外 SDK；签出的 token 与 LiveKit 官方 AccessToken
 * 完全兼容。
 */
@Service
public class LiveKitTokenService {
    /** 与 LiveKit 官方 AccessToken 一致：nbf 回退 1 分钟，容忍客户端与服务端的时钟偏差。 */
    private static final long CLOCK_SKEW_MS = 60_000L;

    @Autowired private LiveKitProperties properties;
    @Autowired private CallService calls;

    /** 签发入房凭证；非参与者、跨家庭、已结束的通话一律拒绝。 */
    public LiveKitTokenVO issue(SessionPrincipal principal, String callId) {
        requireEnabled();
        requireConfigured();

        // detail() 内部已按 principal.householdId 做家庭归属校验，
        // 跨家庭 callId 会被判为 404，不泄漏其它家庭通话是否存在。
        CallVO call = calls.detail(principal, callId);
        requireParticipant(call, principal);
        requireLive(call);

        String room = properties.roomName(callId);
        String token = sign(principal, call, room);
        return new LiveKitTokenVO(
                token, properties.getPublicUrl(), room, properties.getTokenTtlSeconds());
    }

    private void requireEnabled() {
        if (!properties.isEnabled())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SFU 未启用");
    }

    private void requireConfigured() {
        if (!properties.configured())
            throw new IllegalStateException(
                    "LiveKit 未配置完整：请设置 LIVEKIT_API_KEY / LIVEKIT_API_SECRET / LIVEKIT_PUBLIC_URL");
        // HS256 要求密钥至少 256 bit。LiveKit 服务端不校验长度，但 Java 侧必须满足，
        // 否则会在真正签发时才抛 WeakKeyException，排查成本很高。
        if (properties.getApiSecret().getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("LIVEKIT_API_SECRET 至少需要 32 字节，请用 openssl rand -hex 32 生成");
        if (properties.getTokenTtlSeconds() <= 0)
            throw new IllegalStateException("LiveKit token TTL 必须大于 0");
    }

    /**
     * detail() 对管理员放行了非参与者查看，但媒体房不能因此放行——
     * 管理员想排查问题应通过后台而非旁听家庭通话。这里无条件要求参与者身份。
     */
    private void requireParticipant(CallVO call, SessionPrincipal principal) {
        boolean joined =
                call.participants() != null
                        && call.participants().stream()
                                .anyMatch(p -> participantIsActive(p, principal.userId()));
        if (!joined) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不在该通话中");
    }

    private boolean participantIsActive(CallParticipantVO participant, long userId) {
        if (!participant.userId().equals(userId)) return false;
        // 已离开/拒接/未接的人不能再进房；INVITED 允许提前取凭证以便接通瞬间直接入房。
        return Calls.STATE_JOINED.equals(participant.state())
                || Calls.STATE_INVITED.equals(participant.state());
    }

    private void requireLive(CallVO call) {
        if (Calls.STATUS_ENDED.equals(call.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "通话已结束");
    }

    /** 生成 LiveKit 可识别的 JWT：RegisteredClaims + video(VideoGrant)。 */
    private String sign(SessionPrincipal principal, CallVO call, String room) {
        SecretKey key =
                Keys.hmacShaKeyFor(properties.getApiSecret().getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();

        Map<String, Object> video = new LinkedHashMap<>();
        video.put("roomJoin", true);
        video.put("room", room);
        video.put("canPublish", true);
        video.put("canSubscribe", true);
        video.put("canPublishData", true);
        // roomCreate 省略即 false：房间名固定为 family-<callId>，客户端不能自建任意房间。
        // 语音通话只允许发布麦克风，避免误开摄像头把家庭画面推上行。
        if (Calls.KIND_AUDIO.equals(call.kind()))
            video.put("canPublishSources", List.of("microphone"));

        return Jwts.builder()
                .issuer(properties.getApiKey())
                .subject(Long.toString(principal.userId()))
                .claim("name", "成员" + principal.userId())
                .claim("video", video)
                .issuedAt(new Date(now))
                .notBefore(new Date(now - CLOCK_SKEW_MS))
                .expiration(new Date(now + properties.getTokenTtlSeconds() * 1000L))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
