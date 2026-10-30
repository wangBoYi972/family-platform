package com.familyhome.media.vo;

import java.util.List;

/** WebRTC ICE 配置。TURN 使用 coturn 的时效凭据，每次下发都带过期时间，泄露也无法长期使用。 */
public record IceServerVO(List<String> urls, String username, String credential) {
    public static IceServerVO stun(List<String> urls) {
        return new IceServerVO(urls, null, null);
    }
}
