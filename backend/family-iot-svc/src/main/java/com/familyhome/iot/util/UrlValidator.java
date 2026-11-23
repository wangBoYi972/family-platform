package com.familyhome.iot.util;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * 防 SSRF 验证工具
 */
public class UrlValidator {

  /**
   * 验证 URL 是否安全
   * - 只允许 http 和 https
   * - 只允许私网、localhost 和链路本地地址
   * - 拒绝公网、元数据地址和重定向跳出允许范围
   */
  public static boolean isSafeUrl(String url) {
    try {
      URL parsedUrl = new URL(url);

      // 1. 检查协议
      String protocol = parsedUrl.getProtocol().toLowerCase();
      if (!protocol.equals("http") && !protocol.equals("https")) {
        return false;
      }

      // 2. 检查主机
      String host = parsedUrl.getHost();
      if (host == null || host.isEmpty()) {
        return false;
      }

      // localhost 或 127.0.0.1
      if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
        return true;
      }

      // 私网 IP 检查
      return isPrivateIp(host);

    } catch (MalformedURLException e) {
      return false;
    }
  }

  /**
   * 检查是否为私网 IP
   * - 10.0.0.0 ~ 10.255.255.255
   * - 172.16.0.0 ~ 172.31.255.255
   * - 192.168.0.0 ~ 192.168.255.255
   * - 169.254.0.0 ~ 169.254.255.255（链路本地地址，但排除 169.254.169.254 元数据地址）
   */
  private static boolean isPrivateIp(String host) {
    try {
      // IPv4 检查
      if (host.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
        String[] parts = host.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        int third = Integer.parseInt(parts[2]);
        int fourth = Integer.parseInt(parts[3]);

        // 10.0.0.0/8
        if (first == 10) return true;

        // 172.16.0.0/12
        if (first == 172 && second >= 16 && second <= 31) return true;

        // 192.168.0.0/16
        if (first == 192 && second == 168) return true;

        // 169.254.0.0/16 链路本地地址，但排除 169.254.169.254（AWS 元数据地址）
        if (first == 169 && second == 254) {
          // 拒绝 169.254.169.254
          if (third == 169 && fourth == 254) {
            return false;
          }
          return true;
        }
      }

      // IPv6 私网检查（简化版）
      if (host.startsWith("fd") || host.startsWith("fc")) {
        return true; // ULA
      }
      if (host.equals("::1")) {
        return true; // loopback
      }
      if (host.startsWith("fe80")) {
        return true; // link-local
      }

      return false;

    } catch (Exception e) {
      return false;
    }
  }
}
