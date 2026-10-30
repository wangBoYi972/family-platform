package com.familyhome.media.service;

import com.familyhome.media.config.MediaProperties;
import com.familyhome.media.vo.IceServerVO;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 下发 ICE 配置。
 *
 * <p>TURN 采用 coturn 的 <b>时效凭据</b>（REST API 机制）：username = {@code 过期时间戳:用户ID}，
 * credential = Base64(HMAC-SHA1(secret, username))。凭据本身带过期时间，即使被截获也无法长期盗用流量。
 */
@Service
public class IceServerService {
    private static final String HMAC_SHA1 = "HmacSHA1";

    @Autowired private MediaProperties properties;

    public List<IceServerVO> forUser(long userId) {
        var turn = properties.getTurn();
        List<IceServerVO> servers = new ArrayList<>();
        if (!turn.getStunUrls().isEmpty()) servers.add(IceServerVO.stun(turn.getStunUrls()));
        if (turn.getSecret() == null || turn.getSecret().isBlank() || turn.getUrls().isEmpty())
            return servers;
        long expiry = Instant.now().getEpochSecond() + Math.max(60, turn.getTtlSeconds());
        String username = expiry + ":" + userId;
        servers.add(new IceServerVO(turn.getUrls(), username, credential(turn.getSecret(), username)));
        return servers;
    }

    /** coturn 的凭据算法，公开为静态方法以便服务端复算校验与单元测试。 */
    public static String credential(String secret, String username) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("TURN 凭据生成失败", ex);
        }
    }
}
