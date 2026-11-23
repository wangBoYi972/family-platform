package com.familyhome.iot;

import com.familyhome.iot.dto.IotHubBindDTO;
import com.familyhome.iot.service.IotHubService;
import com.familyhome.iot.util.UrlValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IoT 服务集成测试
 */
@SpringBootTest
@ActiveProfiles("test")
public class IotServiceIntegrationTest {

  private static MockHomeAssistantServer mockServer;

  @Autowired(required = false)
  private IotHubService hubService;

  @BeforeAll
  public static void setupMockServer() throws Exception {
    mockServer = new MockHomeAssistantServer(8900);
    mockServer.start();

    // 给服务器启动时间
    Thread.sleep(500);
  }

  @Test
  public void testUrlValidation() {
    // 私网地址允许
    assertTrue(UrlValidator.isSafeUrl("http://192.168.1.100:8123"));
    assertTrue(UrlValidator.isSafeUrl("http://10.0.0.1:8123"));
    assertTrue(UrlValidator.isSafeUrl("http://172.16.0.1:8123"));
    assertTrue(UrlValidator.isSafeUrl("http://localhost:8123"));
    assertTrue(UrlValidator.isSafeUrl("http://127.0.0.1:8123"));

    // 公网地址拒绝
    assertFalse(UrlValidator.isSafeUrl("http://8.8.8.8:8123"));
    assertFalse(UrlValidator.isSafeUrl("http://1.1.1.1:8123"));

    // 元数据地址拒绝
    assertFalse(UrlValidator.isSafeUrl("http://169.254.169.254:8123"));

    // 非 HTTP 协议拒绝
    assertFalse(UrlValidator.isSafeUrl("file:///etc/passwd"));
  }

  @Test
  public void testConnectionValid() throws Exception {
    if (hubService == null) return;

    IotHubBindDTO dto = new IotHubBindDTO();
    dto.setBaseUrl("http://127.0.0.1:8900");
    dto.setAccessToken("test-token-123");

    boolean result = hubService.testConnection(dto);
    assertTrue(result, "应该成功连接到模拟服务器");
  }

  @Test
  public void testConnectionInvalidToken() throws Exception {
    if (hubService == null) return;

    IotHubBindDTO dto = new IotHubBindDTO();
    dto.setBaseUrl("http://127.0.0.1:8900");
    dto.setAccessToken("invalid-token");

    boolean result = hubService.testConnection(dto);
    assertFalse(result, "错误令牌应该连接失败");
  }

  @Test
  public void testConnectionPublicAddress() throws Exception {
    if (hubService == null) return;

    IotHubBindDTO dto = new IotHubBindDTO();
    dto.setBaseUrl("http://8.8.8.8:8123");
    dto.setAccessToken("test-token-123");

    assertThrows(IllegalArgumentException.class, () -> {
      hubService.testConnection(dto);
    }, "公网地址应该被拒绝");
  }
}
