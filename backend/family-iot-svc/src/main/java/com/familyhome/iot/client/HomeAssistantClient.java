package com.familyhome.iot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.iot.model.IotHub;
import com.familyhome.iot.util.UrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Home Assistant HTTP 客户端
 */
@Slf4j
@Component
public class HomeAssistantClient {

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  private final HttpClient httpClient;
  private final int commandTimeout;
  private final ObjectMapper objectMapper;

  public HomeAssistantClient(com.familyhome.iot.config.IotConfig iotConfig) {
    this.httpClient = HttpClient.newHttpClient();
    this.commandTimeout = iotConfig.getCommandTimeout();
    this.objectMapper = new ObjectMapper();
  }

  /**
   * 测试连接
   */
  public boolean testConnection(String baseUrl, String accessToken) {
    try {
      if (!UrlValidator.isSafeUrl(baseUrl)) {
        log.warn("不安全的 URL: {}", baseUrl);
        return false;
      }

      String url = baseUrl.endsWith("/") ? baseUrl + "api/" : baseUrl + "/api/";
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofSeconds(commandTimeout))
          .header(AUTHORIZATION_HEADER, BEARER_PREFIX + accessToken)
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200;
    } catch (Exception e) {
      log.error("测试 Home Assistant 连接失败", e);
      return false;
    }
  }

  /**
   * 获取所有实体状态
   */
  public JsonNode getStates(IotHub hub) throws Exception {
    if (!UrlValidator.isSafeUrl(hub.getBaseUrl())) {
      throw new IllegalArgumentException("不安全的 URL");
    }

    String url = hub.getBaseUrl().endsWith("/") ? hub.getBaseUrl() + "api/states" : hub.getBaseUrl() + "/api/states";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(commandTimeout))
        .header(AUTHORIZATION_HEADER, BEARER_PREFIX + hub.getAccessToken())
        .GET()
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new RuntimeException("获取状态失败: " + response.statusCode());
    }

    return objectMapper.readTree(response.body());
  }

  /**
   * 执行服务调用
   */
  public boolean callService(IotHub hub, String domain, String service, JsonNode data) throws Exception {
    if (!UrlValidator.isSafeUrl(hub.getBaseUrl())) {
      throw new IllegalArgumentException("不安全的 URL");
    }

    String baseUrl = hub.getBaseUrl();
    if (!baseUrl.endsWith("/")) {
      baseUrl = baseUrl + "/";
    }
    String url = baseUrl + "api/services/" + domain + "/" + service;

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(commandTimeout))
        .header(AUTHORIZATION_HEADER, BEARER_PREFIX + hub.getAccessToken())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(data)))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      return true;
    }

    log.warn("Home Assistant 服务调用失败: {} {} 返回 {}", domain, service, response.statusCode());
    return false;
  }
}
