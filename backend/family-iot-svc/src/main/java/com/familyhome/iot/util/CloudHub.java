package com.familyhome.iot.util;

/**
 * 云端直连模式标识（涂鸦等）。不是 Home Assistant，普通家庭无需自建中枢。
 */
public final class CloudHub {
  public static final String CLOUD_BASE_URL = "cloud://tuya";

  private CloudHub() {}

  public static boolean isCloud(String baseUrl) {
    return baseUrl != null && baseUrl.trim().toLowerCase().startsWith("cloud://");
  }
}
