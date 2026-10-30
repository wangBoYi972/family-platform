package com.familyhome.media.service;

import java.util.List;
import java.util.Map;

/**
 * 一条通话信令。
 *
 * <p>{@code to} 为空表示房间内广播（服务端按数据库中的参与者列表展开），
 * 非空表示只投递给指定用户（WebRTC 的 offer/answer/ICE 必须点对点）。
 */
public record CallSignal(
        String type,
        String callId,
        Long from,
        Long householdId,
        List<Long> to,
        Map<String, Object> data) {}
