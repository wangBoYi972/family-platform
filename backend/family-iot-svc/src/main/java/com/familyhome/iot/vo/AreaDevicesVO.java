package com.familyhome.iot.vo;

import java.util.List;

/**
 * 区域设备分组VO
 */
public class AreaDevicesVO {
  private String areaName;
  private List<IotDeviceVO> devices;

  public AreaDevicesVO() {}

  public AreaDevicesVO(String areaName, List<IotDeviceVO> devices) {
    this.areaName = areaName;
    this.devices = devices;
  }

  public String getAreaName() {
    return areaName;
  }

  public void setAreaName(String areaName) {
    this.areaName = areaName;
  }

  public List<IotDeviceVO> getDevices() {
    return devices;
  }

  public void setDevices(List<IotDeviceVO> devices) {
    this.devices = devices;
  }
}
