package com.familyhome.media.vo;

/** LiveKit 入房凭证，前端拿到后直接交给 livekit-client 的 room.connect(url, token)。 */
public record LiveKitTokenVO(
        /** JWT，含 video grant（room / roomJoin / canPublish / canSubscribe）。 */
        String token,
        /** 浏览器连接的 WSS 地址。 */
        String url,
        /** 服务端生成的房间名，等于 roomPrefix + callId。 */
        String roomName,
        /** 凭证有效期（秒），前端可据此提前刷新。 */
        int ttlSeconds) {}
