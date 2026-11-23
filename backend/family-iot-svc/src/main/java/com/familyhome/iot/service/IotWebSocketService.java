package com.familyhome.iot.service;

/**
 * WebSocket 连接服务
 */
public interface IotWebSocketService {

  /**
   * 为某个家庭建立 WebSocket 连接
   */
  void connect(Long householdId) throws Exception;

  /**
   * 关闭 WebSocket 连接
   */
  void disconnect(Long householdId);

  /**
   * 检查连接状态
   */
  boolean isConnected(Long householdId);
}
