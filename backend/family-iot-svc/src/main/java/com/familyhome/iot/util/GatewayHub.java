package com.familyhome.iot.util;

/**
 * 家侧网关中枢标识。
 *
 * <p>云端不直连私网设备；家里跑一个网关进程，出站连云。中枢地址固定为
 * {@link #GATEWAY_BASE_URL}，真实控制由在线网关会话执行。
 */
public final class GatewayHub {

  public static final String GATEWAY_BASE_URL = "gateway://home";

  private GatewayHub() {}

  public static boolean isGateway(String baseUrl) {
    return baseUrl != null && baseUrl.trim().toLowerCase().startsWith("gateway://");
  }
}
