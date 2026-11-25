package com.familyhome.iot;

import com.familyhome.iot.util.HubUrlValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中枢地址白名单校验测试
 *
 * <p>安全基线：私网默认放行；公网/组网地址必须显式加入白名单，否则拒绝（防 SSRF）。
 */
class HubUrlValidatorTest {

  @Test
  void privateNetworkAlwaysAllowed() {
    HubUrlValidator v = new HubUrlValidator("");
    assertTrue(v.isAllowed("http://192.168.1.100:8123"));
    assertTrue(v.isAllowed("http://10.0.0.5:8123"));
    assertTrue(v.isAllowed("http://localhost:8123"));
    assertFalse(v.hasRemoteAllowlist());
  }

  @Test
  void publicAddressRejectedWithoutAllowlist() {
    HubUrlValidator v = new HubUrlValidator("");
    assertFalse(v.isAllowed("https://myhome.example.com"));
    assertFalse(v.isAllowed("https://8.8.8.8:8123"));
  }

  @Test
  void allowlistedHostAllowed() {
    HubUrlValidator v = new HubUrlValidator("abc123.ts.net, myhome.ui.nabu.casa");
    assertTrue(v.hasRemoteAllowlist());
    assertTrue(v.isAllowed("https://abc123.ts.net:8123"));
    assertTrue(v.isAllowed("https://myhome.ui.nabu.casa"));
  }

  @Test
  void allowlistIsExactHostNotSuffix() {
    HubUrlValidator v = new HubUrlValidator("home.example.com");
    assertTrue(v.isAllowed("https://home.example.com"));
    // 后缀匹配被禁止，避免 evil-home.example.com 绕过
    assertFalse(v.isAllowed("https://evil-home.example.com"));
    assertFalse(v.isAllowed("https://sub.home.example.com"));
  }

  @Test
  void metadataAndMalformedRejected() {
    HubUrlValidator v = new HubUrlValidator("169.254.169.254");
    // 云元数据地址即使在白名单里也拒绝（UrlValidator 层拦截）
    assertFalse(v.isAllowed("http://169.254.169.254/latest/meta-data"));
    assertFalse(v.isAllowed("not a url"));
    assertFalse(v.isAllowed(""));
    assertFalse(v.isAllowed(null));
  }

  @Test
  void demoHubAllowed() {
    HubUrlValidator v = new HubUrlValidator("");
    assertTrue(v.isAllowed("demo://local"));
  }
}
