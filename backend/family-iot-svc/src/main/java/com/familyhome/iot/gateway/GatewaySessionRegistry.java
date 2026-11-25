package com.familyhome.iot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 家侧网关出站 WebSocket 会话表：按家庭号索引，支持命令下发与 ACK 等待。
 */
@Component
public class GatewaySessionRegistry {
  private static final Logger log = LoggerFactory.getLogger(GatewaySessionRegistry.class);

  private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
  private final ObjectMapper mapper = new ObjectMapper();

  public void bind(Long householdId, WebSocketSession session) {
    WebSocketSession previous = sessions.put(householdId, session);
    if (previous != null && previous.isOpen() && !previous.getId().equals(session.getId())) {
      try {
        previous.close();
      } catch (IOException ignored) {
        // ignore
      }
    }
  }

  public void unbind(Long householdId, WebSocketSession session) {
    sessions.computeIfPresent(
        householdId,
        (id, current) -> current.getId().equals(session.getId()) ? null : current);
  }

  public boolean isOnline(Long householdId) {
    WebSocketSession session = sessions.get(householdId);
    return session != null && session.isOpen();
  }

  public void complete(String requestId, JsonNode body) {
    CompletableFuture<JsonNode> future = pending.remove(requestId);
    if (future != null) {
      future.complete(body);
    }
  }

  public JsonNode sendCommand(
      Long householdId, String entityId, String commandType, JsonNode parameters, long timeoutMs)
      throws Exception {
    WebSocketSession session = sessions.get(householdId);
    if (session == null || !session.isOpen()) {
      throw new IllegalStateException("家庭网关未在线，无法控制设备");
    }
    String requestId = UUID.randomUUID().toString().replace("-", "");
    ObjectNode frame = mapper.createObjectNode();
    frame.put("type", "command");
    frame.put("requestId", requestId);
    frame.put("entityId", entityId);
    frame.put("commandType", commandType);
    if (parameters != null && !parameters.isNull()) {
      frame.set("parameters", parameters);
    } else {
      frame.putObject("parameters");
    }

    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pending.put(requestId, future);
    try {
      synchronized (session) {
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
      }
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      pending.remove(requestId);
      throw new IllegalStateException("网关执行超时");
    } catch (Exception ex) {
      pending.remove(requestId);
      throw ex;
    }
  }

  public void sendJson(Long householdId, ObjectNode frame) {
    WebSocketSession session = sessions.get(householdId);
    if (session == null || !session.isOpen()) return;
    try {
      synchronized (session) {
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
      }
    } catch (Exception e) {
      log.warn("向家庭 {} 网关推送失败: {}", householdId, e.getMessage());
    }
  }
}
