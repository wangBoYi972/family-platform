package com.familyhome.iot.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 中枢地址校验（带远程白名单）
 *
 * <p>安全基线：默认只允许私网地址，防止 SSRF 打云元数据/内网探测。
 * 但用户在家以外要控制设备，必须能让后端访问远程中枢（Tailscale 组网 /
 * Home Assistant Cloud / 内网穿透域名）。因此引入显式白名单：
 *
 * <ul>
 *   <li>私网 / localhost：始终允许（原行为不变）</li>
 *   <li>公网 / 组网地址：仅当主机名在 {@code family.iot.allowed-hosts} 中才允许</li>
 * </ul>
 *
 * <p>白名单按「精确主机名」匹配，不做后缀通配符，避免 *\.example.com 之类的绕过。
 */
@Component
public class HubUrlValidator {

  private final Set<String> allowedHosts;

  public HubUrlValidator(
      @Value("${family.iot.allowed-hosts:}") String allowedHosts) {
    this.allowedHosts = Arrays.stream(allowedHosts.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> s.toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }

  /** 校验地址是否可用作中枢地址。 */
  public boolean isAllowed(String url) {
    if (url == null || url.isBlank()) return false;
    // 演示 / 云端直连是伪协议，不走 HA HTTP，直接放行
    if (DemoHub.isDemo(url) || CloudHub.isCloud(url)) return true;

    // 第一层：原有 SSRF 防护（私网/localhost 直接通过）
    if (UrlValidator.isSafeUrl(url)) return true;

    // 第二层：白名单里的远程主机。
    // 关键：必须先排除云元数据等危险地址，否则用户误把 169.254.169.254 配进白名单会打穿 SSRF 防护。
    if (allowedHosts.isEmpty()) return false;
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (host == null || host.isBlank()) return false;
      String lower = host.toLowerCase(Locale.ROOT);
      if (isDangerousHost(lower)) return false;
      return allowedHosts.contains(lower);
    } catch (Exception e) {
      return false;
    }
  }

  /** 云元数据 / 本地回环等危险地址，即使写进白名单也拒绝。 */
  private boolean isDangerousHost(String host) {
    if (host.equals("169.254.169.254")) return true; // AWS/GCP/Azure 元数据
    if (host.equals("metadata.google.internal")) return true;
    if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) return true;
    // 云元数据网段 169.254.0.0/16 整体拒绝
    if (host.startsWith("169.254.")) return true;
    return false;
  }

  /** 供前端展示：是否已配置远程白名单。 */
  public boolean hasRemoteAllowlist() {
    return !allowedHosts.isEmpty();
  }
}
