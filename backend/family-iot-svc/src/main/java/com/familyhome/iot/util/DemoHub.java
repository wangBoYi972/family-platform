package com.familyhome.iot.util;

/**
 * 演示中枢标识。
 *
 * <p>没有真实 Home Assistant 的家庭（例如课堂演示、评审环境）可以绑定演示中枢：
 * 地址固定为 {@link #DEMO_BASE_URL}，设备来自内置示例数据，命令只改本地快照状态，
 * 不会发出任何网络请求。家庭成员的操作路径与真实中枢完全一致。
 */
public final class DemoHub {

  public static final String DEMO_BASE_URL = "demo://local";

  private DemoHub() {}

  public static boolean isDemo(String baseUrl) {
    return baseUrl != null && baseUrl.trim().toLowerCase().startsWith("demo://");
  }
}
