package com.familyhome.iot;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * 模拟 Home Assistant 服务器
 *
 * 用于集成测试
 */
public class MockHomeAssistantServer {

  private HttpServer server;
  private final int port;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public MockHomeAssistantServer(int port) {
    this.port = port;
  }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

    // /api/ - 验证连接
    server.createContext("/api/", exchange -> handleApi(exchange));

    // /api/states - 获取所有实体状态
    server.createContext("/api/states", exchange -> handleStates(exchange));

    // /api/services/{domain}/{service} - 执行服务
    server.createContext("/api/services/", exchange -> handleService(exchange));

    server.setExecutor(null);
    server.start();
    System.out.println("Mock Home Assistant 已启动在端口 " + port);
  }

  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private void handleApi(HttpExchange exchange) throws IOException {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");

    if (!isValidToken(auth)) {
      sendResponse(exchange, 401, "{\"error\": \"unauthorized\"}");
      return;
    }

    sendResponse(exchange, 200, "{\"message\": \"API running\"}");
  }

  private void handleStates(HttpExchange exchange) throws IOException {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");

    if (!isValidToken(auth)) {
      sendResponse(exchange, 401, "{\"error\": \"unauthorized\"}");
      return;
    }

    // 返回模拟设备列表
    String states = "[" +
        "{\"entity_id\": \"light.living_room\", \"state\": \"on\", \"attributes\": {\"friendly_name\": \"客厅灯\", \"area_id\": \"living_room\", \"brightness\": 200}}," +
        "{\"entity_id\": \"light.bedroom\", \"state\": \"off\", \"attributes\": {\"friendly_name\": \"卧室灯\", \"area_id\": \"bedroom\", \"brightness\": 0}}," +
        "{\"entity_id\": \"switch.outlet\", \"state\": \"on\", \"attributes\": {\"friendly_name\": \"插座\", \"area_id\": \"living_room\"}}," +
        "{\"entity_id\": \"sensor.temp\", \"state\": \"22.5\", \"attributes\": {\"friendly_name\": \"温度\", \"area_id\": \"living_room\", \"unit_of_measurement\": \"°C\"}}," +
        "{\"entity_id\": \"scene.movie\", \"state\": \"unknown\", \"attributes\": {\"friendly_name\": \"电影模式\", \"area_id\": \"living_room\"}}" +
        "]";

    sendResponse(exchange, 200, states);
  }

  private void handleService(HttpExchange exchange) throws IOException {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");

    if (!isValidToken(auth)) {
      sendResponse(exchange, 401, "{\"error\": \"unauthorized\"}");
      return;
    }

    // 解析请求路径：/api/services/{domain}/{service}
    String path = exchange.getRequestURI().getPath();
    String[] parts = path.split("/");

    if (parts.length < 5) {
      sendResponse(exchange, 400, "{\"error\": \"invalid path\"}");
      return;
    }

    String domain = parts[3];
    String service = parts[4];

    // 白名单检查
    if (!isAllowedCommand(domain, service)) {
      sendResponse(exchange, 400, "{\"error\": \"service not allowed\"}");
      return;
    }

    // 解析请求体
    String body = new String(exchange.getRequestBody().readAllBytes());

    // 模拟命令执行
    sendResponse(exchange, 200, "{\"success\": true}");
  }

  private boolean isValidToken(String authHeader) {
    return authHeader != null && authHeader.equals("Bearer test-token-123");
  }

  private boolean isAllowedCommand(String domain, String service) {
    Set<String> allowed = Set.of(
        "light.turn_on",
        "light.turn_off",
        "switch.turn_on",
        "switch.turn_off",
        "scene.turn_on"
    );
    return allowed.contains(domain + "." + service);
  }

  private void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, body.getBytes().length);
    exchange.getResponseBody().write(body.getBytes());
    exchange.close();
  }

  public static void main(String[] args) throws IOException {
    MockHomeAssistantServer server = new MockHomeAssistantServer(8900);
    server.start();

    // 保持运行
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
  }
}
