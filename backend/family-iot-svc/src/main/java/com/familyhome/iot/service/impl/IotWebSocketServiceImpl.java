package com.familyhome.iot.service.impl;

import com.familyhome.iot.service.IotWebSocketService;
import com.familyhome.iot.service.IotDeviceService;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.util.UrlValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * WebSocket 连接服务实现
 *
 * 维持到 Home Assistant 的 WebSocket 连接，接收 state_changed 事件
 */
@Slf4j
@Service
public class IotWebSocketServiceImpl implements IotWebSocketService {

  private final Map<Long, WebSocket> wsConnections = new HashMap<>();
  private final HttpClient httpClient;

  @Autowired
  private IotHubService hubService;

  @Autowired
  private IotDeviceService deviceService;

  @Autowired
  private com.familyhome.iot.config.IotConfig iotConfig;

  public IotWebSocketServiceImpl() {
    this.httpClient = HttpClient.newHttpClient();
  }

  @Override
  public void connect(Long householdId) throws Exception {
    // 检查是否已连接
    if (wsConnections.containsKey(householdId)) {
      log.debug("家庭 {} 已连接", householdId);
      return;
    }

    var hub = hubService.getHubInternal(householdId);
    if (hub == null) {
      throw new RuntimeException("中枢未配置");
    }

    String wsUrl = hub.getBaseUrl()
        .replaceAll("^https?://", "")  // 移除协议头
        .replaceAll("/$", "");         // 移除尾部斜杠

    // 安全检查：只允许私网地址的 WebSocket 连接
    if (!UrlValidator.isSafeUrl(hub.getBaseUrl())) {
      throw new IllegalArgumentException("WebSocket 地址不安全");
    }

    // 拼接完整的 WebSocket URL
    String fullWsUrl = "ws://" + wsUrl + "/api/websocket";

    log.info("正在连接家庭 {} 的中枢 WebSocket: {}", householdId, fullWsUrl);

    WebSocket.Listener listener = new IotWebSocketListener(householdId, hub.getAccessToken(), deviceService);

    CompletableFuture<WebSocket> cf = httpClient.newWebSocketBuilder()
        .buildAsync(URI.create(fullWsUrl), listener);

    try {
      WebSocket ws = cf.join();
      wsConnections.put(householdId, ws);
    } catch (Exception e) {
      log.error("WebSocket 连接失败", e);
      throw e;
    }
  }

  @Override
  public void disconnect(Long householdId) {
    WebSocket ws = wsConnections.get(householdId);
    if (ws != null) {
      ws.sendClose(WebSocket.NORMAL_CLOSURE, "close");
      wsConnections.remove(householdId);
      log.info("家庭 {} 的 WebSocket 已断开", householdId);

      // 标记中枢离线
      deviceService.markHubOffline(householdId);
    }
  }

  @Override
  public boolean isConnected(Long householdId) {
    return wsConnections.containsKey(householdId);
  }
}

/**
 * WebSocket 事件监听器
 */
@Slf4j
class IotWebSocketListener implements WebSocket.Listener {

  private final Long householdId;
  private final String accessToken;
  private final IotDeviceService deviceService;
  private final ObjectMapper objectMapper;

  public IotWebSocketListener(Long householdId, String accessToken, IotDeviceService deviceService) {
    this.householdId = householdId;
    this.accessToken = accessToken;
    this.deviceService = deviceService;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public void onOpen(WebSocket webSocket) {
    log.info("WebSocket 连接已打开：家庭 {}", householdId);
    // 发送认证信息
    try {
      String auth = objectMapper.writeValueAsString(
          Map.of("type", "auth", "access_token", accessToken)
      );
      webSocket.sendText(auth, true);
    } catch (Exception e) {
      log.error("发送认证信息失败", e);
      webSocket.abort();
    }
  }

  @Override
  public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    try {
      JsonNode msg = objectMapper.readTree(data.toString());
      String type = msg.path("type").asText();

      if ("auth_ok".equals(type)) {
        log.info("WebSocket 认证成功：家庭 {}", householdId);
        // 订阅事件
        String subscribe = objectMapper.writeValueAsString(
            Map.of("id", 1, "type", "subscribe_events", "event_type", "state_changed")
        );
        webSocket.sendText(subscribe, true);
      } else if ("event".equals(type)) {
        // 处理状态变化事件
        processStateChange(msg);
      }
    } catch (Exception e) {
      log.error("处理 WebSocket 消息失败", e);
    }
    return new CompletableFuture<>().completedFuture(null);
  }

  @Override
  public CompletableFuture<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
    return new CompletableFuture<>().completedFuture(null);
  }

  @Override
  public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    log.warn("WebSocket 已关闭：家庭 {}，原因: {}", householdId, reason);
    return new CompletableFuture<>().completedFuture(null);
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    log.error("WebSocket 错误：家庭 {}", householdId, error);
  }

  private void processStateChange(JsonNode msg) {
    try {
      var data = msg.path("data").path("new_state");
      if (!data.isMissingNode()) {
        String entityId = data.path("entity_id").asText();
        String state = data.path("state").asText();
        var attributes = objectMapper.convertValue(
            data.path("attributes"),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        );

        deviceService.updateDeviceState(householdId, entityId, state, attributes);

        log.debug("设备状态已更新：{} -> {}", entityId, state);
      }
    } catch (Exception e) {
      log.error("处理状态变化失败", e);
    }
  }
}
