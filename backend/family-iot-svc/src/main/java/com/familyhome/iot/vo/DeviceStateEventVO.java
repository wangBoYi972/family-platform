package com.familyhome.iot.vo;

/**
 * 设备状态变化事件VO（用于 Outbox 事件发布）
 */
public class DeviceStateEventVO {
  private String entityId;
  private String deviceName;
  private String state;
  private long updatedAt;

  public DeviceStateEventVO() {}

  public DeviceStateEventVO(String entityId, String deviceName, String state, long updatedAt) {
    this.entityId = entityId;
    this.deviceName = deviceName;
    this.state = state;
    this.updatedAt = updatedAt;
  }

  public String getEntityId() {
    return entityId;
  }

  public void setEntityId(String entityId) {
    this.entityId = entityId;
  }

  public String getDeviceName() {
    return deviceName;
  }

  public void setDeviceName(String deviceName) {
    this.deviceName = deviceName;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public long getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(long updatedAt) {
    this.updatedAt = updatedAt;
  }
}
