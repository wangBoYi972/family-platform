package com.familyhome.iot.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 涂鸦开放平台云云对接（中国区默认）。
 *
 * <p>落地路径：手机 App → family-iot-svc → 涂鸦云 → 家里 Wi‑Fi 红外伴侣 / 智能空调。
 * 用户无需安装 Home Assistant。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "family.iot.cloud.provider", havingValue = "tuya")
public class TuyaCloudClient implements IotCloudClient {

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  @Value("${family.iot.cloud.tuya.base-url:https://openapi.tuyacn.com}")
  private String baseUrl;

  @Value("${family.iot.cloud.tuya.access-id:}")
  private String accessId;

  @Value("${family.iot.cloud.tuya.access-secret:}")
  private String accessSecret;

  @Value("${family.iot.cloud.tuya.uid:}")
  private String uid;

  private volatile String cachedToken;
  private volatile long tokenExpireAtMs;

  @Override
  public boolean isReady() {
    return notBlank(accessId) && notBlank(accessSecret) && notBlank(uid);
  }

  @Override
  public String providerId() {
    return "tuya";
  }

  @Override
  public List<CloudDeviceInfo> listDevices() throws Exception {
    ensureReady();
    String path = "/v1.0/users/" + uid + "/devices";
    JsonNode root = signedGet(path);
    List<CloudDeviceInfo> out = new ArrayList<>();
    JsonNode result = root.path("result");
    if (!result.isArray()) return out;
    for (JsonNode row : result) {
      CloudDeviceInfo info = new CloudDeviceInfo();
      info.setCloudDeviceId(text(row, "id"));
      info.setName(text(row, "name"));
      info.setBrand(text(row, "product_name"));
      info.setCategory(text(row, "category"));
      info.setOnline(row.path("online").asBoolean(false));
      info.setEntityType(mapCategory(info.getCategory()));
      if (info.getCloudDeviceId() != null) out.add(info);
    }
    return out;
  }

  @Override
  public boolean sendCommand(String cloudDeviceId, String entityType, String commandType, JsonNode parameters)
      throws Exception {
    ensureReady();
    ArrayNode commands = mapper.createArrayNode();
    ObjectNode cmd = commands.addObject();
    // 通用开关类用 switch；空调/红外伴侣按 code 映射，可由 attributes.tuyaCode 覆盖
    String code = mapCommandCode(entityType, commandType, parameters);
    cmd.put("code", code);
    cmd.set("value", mapCommandValue(commandType, parameters));

    ObjectNode body = mapper.createObjectNode();
    body.set("commands", commands);
    String path = "/v1.0/devices/" + cloudDeviceId + "/commands";
    JsonNode root = signedPost(path, body.toString());
    return root.path("success").asBoolean(false);
  }

  private void ensureReady() {
    if (!isReady()) {
      throw new IllegalStateException("涂鸦云未配置：请设置 FAMILY_IOT_TUYA_ACCESS_ID/SECRET/UID");
    }
  }

  private String accessToken() throws Exception {
    long now = System.currentTimeMillis();
    if (cachedToken != null && now < tokenExpireAtMs - 60_000) return cachedToken;
    String path = "/v1.0/token?grant_type=1";
    JsonNode root = signedGet(path, false);
    if (!root.path("success").asBoolean(false)) {
      throw new IllegalStateException("获取涂鸦 token 失败: " + root.path("msg").asText());
    }
    cachedToken = root.path("result").path("access_token").asText();
    long expire = root.path("result").path("expire_time").asLong(7200);
    tokenExpireAtMs = now + expire * 1000;
    return cachedToken;
  }

  private JsonNode signedGet(String path) throws Exception {
    return signedGet(path, true);
  }

  private JsonNode signedGet(String path, boolean withToken) throws Exception {
    String token = withToken ? accessToken() : "";
    long t = System.currentTimeMillis();
    String stringToSign = "GET\n" + sha256Hex("") + "\n\n" + path;
    String sign = sign(accessId + token + t + stringToSign);
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create(trimSlash(baseUrl) + path))
        .timeout(Duration.ofSeconds(12))
        .header("client_id", accessId)
        .header("sign", sign)
        .header("t", String.valueOf(t))
        .header("sign_method", "HMAC-SHA256")
        .GET();
    if (withToken) b.header("access_token", token);
    HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return mapper.readTree(res.body());
  }

  private JsonNode signedPost(String path, String bodyJson) throws Exception {
    String token = accessToken();
    long t = System.currentTimeMillis();
    String stringToSign = "POST\n" + sha256Hex(bodyJson) + "\n\n" + path;
    String sign = sign(accessId + token + t + stringToSign);
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(trimSlash(baseUrl) + path))
        .timeout(Duration.ofSeconds(12))
        .header("client_id", accessId)
        .header("access_token", token)
        .header("sign", sign)
        .header("t", String.valueOf(t))
        .header("sign_method", "HMAC-SHA256")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
        .build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    return mapper.readTree(res.body());
  }

  private static String mapCategory(String category) {
    if (category == null) return "switch";
    String c = category.toLowerCase();
    if (c.contains("air") || c.contains("kt") || c.equals("ac")) return "climate";
    if (c.contains("tv") || c.contains("dj") || c.contains("media")) return "media_player";
    if (c.contains("light") || c.contains("djb")) return "light";
    if (c.contains("sensor")) return "sensor";
    return "switch";
  }

  private static String mapCommandCode(String entityType, String commandType, JsonNode parameters) {
    if (parameters != null && parameters.hasNonNull("tuyaCode")) {
      return parameters.get("tuyaCode").asText();
    }
    if ("set_temperature".equals(commandType)) return "temp_set";
    if ("set_hvac_mode".equals(commandType)) return "mode";
    if ("set_fan_mode".equals(commandType)) return "fan_speed_enum";
    if ("volume_set".equals(commandType)) return "volume_set";
    if ("volume_mute".equals(commandType)) return "mute";
    if ("select_source".equals(commandType)) return "source";
    if (commandType != null && commandType.startsWith("media_")) return commandType;
    if ("turn_off".equals(commandType)) return "switch";
    return "switch";
  }

  private JsonNode mapCommandValue(String commandType, JsonNode parameters) {
    ObjectNode v = new ObjectMapper().createObjectNode();
    if ("set_temperature".equals(commandType) && parameters != null && parameters.has("temperature")) {
      return parameters.get("temperature");
    }
    if ("set_hvac_mode".equals(commandType) && parameters != null && parameters.has("hvac_mode")) {
      return parameters.get("hvac_mode");
    }
    if ("set_fan_mode".equals(commandType) && parameters != null && parameters.has("fan_mode")) {
      return parameters.get("fan_mode");
    }
    if ("volume_set".equals(commandType) && parameters != null && parameters.has("volume_level")) {
      double level = parameters.get("volume_level").asDouble(0);
      return new ObjectMapper().getNodeFactory().numberNode((int) Math.round(level * 100));
    }
    if ("volume_mute".equals(commandType) && parameters != null && parameters.has("is_volume_muted")) {
      return parameters.get("is_volume_muted");
    }
    if ("select_source".equals(commandType) && parameters != null && parameters.has("source")) {
      return parameters.get("source");
    }
    if ("turn_off".equals(commandType)) {
      return new ObjectMapper().getNodeFactory().booleanNode(false);
    }
    // turn_on 等默认开
    return new ObjectMapper().getNodeFactory().booleanNode(true);
  }

  private String sign(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(accessSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(raw.length * 2);
    for (byte b : raw) sb.append(String.format("%02X", b));
    return sb.toString();
  }

  private static String sha256Hex(String body) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    byte[] raw = digest.digest(body.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(raw.length * 2);
    for (byte b : raw) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static String trimSlash(String u) {
    return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
  }
}
