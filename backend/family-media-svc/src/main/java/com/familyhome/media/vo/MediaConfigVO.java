package com.familyhome.media.vo;

/**
 * 下发给前端的媒体层配置。前端据此在 mesh 与 LiveKit SFU 之间切换，
 * 两个端点永远只下发当前启用的那一种，避免前端拿到无用的地址。
 */
public record MediaConfigVO(
        /** true=走 LiveKit SFU，false=走原有 WebRTC mesh。 */
        boolean sfuEnabled,
        /** SFU 模式下浏览器连接的 WSS 地址；mesh 模式下为 null。 */
        String publicUrl,
        /** 当前有效的人数上限（SFU 20+ / mesh 8），前端选人列表据此限制。 */
        int maxParticipants) {}
