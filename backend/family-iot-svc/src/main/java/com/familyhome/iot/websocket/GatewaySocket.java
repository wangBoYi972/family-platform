package com.familyhome.iot.websocket;

import com.familyhome.iot.gateway.GatewaySessionRegistry;
import com.familyhome.iot.model.IotGateway;
import com.familyhome.iot.service.HomeGatewayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 家侧网关出站 WebSocket：握手带 gatewayToken，连上后接收 command.ack。
 */
@Configuration
@EnableWebSocket
public class GatewaySocket extends TextWebSocketHandler implements WebSocketConfigurer {
  private static final Logger log = LoggerFactory.getLogger(GatewaySocket.class);
  private static final String ATTR_HOUSEHOLD = "householdId";
  private static final String ATTR_GATEWAY = "gatewayId";

  @Autowired private HomeGatewayService homeGatewayService;
  @Autowired private GatewaySessionRegistry sessionRegistry;

  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(this, "/ws/iot-gateway")
        .addInterceptors(new AuthInterceptor())
        .setAllowedOrigins("*");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    Long householdId = (Long) session.getAttributes().get(ATTR_HOUSEHOLD);
    sessionRegistry.bind(householdId, session);
    log.info("家庭网关已上线 household={} gateway={}", householdId, session.getAttributes().get(ATTR_GATEWAY));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    JsonNode root = mapper.readTree(message.getPayload());
    String type = root.path("type").asText();
    if ("command.ack".equals(type) || "command.result".equals(type)) {
      String requestId = root.path("requestId").asText();
      if (!requestId.isBlank()) {
        sessionRegistry.complete(requestId, root);
      }
      return;
    }
    if ("ping".equals(type)) {
      session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    Long householdId = (Long) session.getAttributes().get(ATTR_HOUSEHOLD);
    if (householdId != null) {
      sessionRegistry.unbind(householdId, session);
      log.info("家庭网关已下线 household={} status={}", householdId, status);
    }
  }

  private final class AuthInterceptor implements HandshakeInterceptor {
    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes) {
      try {
        URI uri = request.getURI();
        var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        String token = params.getFirst("token");
        if (token == null || token.isBlank()) {
          token = params.getFirst("gatewayToken");
        }
        IotGateway gateway = homeGatewayService.authenticate(token);
        homeGatewayService.touch(gateway);
        attributes.put(ATTR_HOUSEHOLD, gateway.getHouseholdId());
        attributes.put(ATTR_GATEWAY, gateway.getGatewayId());
        return true;
      } catch (Exception e) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
      }
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Exception exception) {}
  }
}
