package com.familyhome.iot.vo;

/**
 * 单台设备响应探测结果
 *
 * <p>区分两种来源：
 * <ul>
 *   <li>真实中枢设备（HUB）：探测会真正访问 Home Assistant 读取实体实时状态，
 *       {@code realHardware=true}，成功/失败反映设备是否真的在线响应。</li>
 *   <li>手动添加 / 演示设备（MANUAL / demo）：{@code realHardware=false}，不涉及任何真实硬件，
 *       {@code message} 会明确说明这是本地模拟。</li>
 * </ul>
 */
public class DeviceTestResultVO {

  /** 是否探测成功（真实设备=是否读到实时状态；模拟设备恒为 true 表示操作本身完成） */
  private boolean ok;

  /** 是否为真实硬件探测（false=本地模拟，无真实设备） */
  private boolean realHardware;

  /** 探测到的实时状态（仅真实设备且 ok 时有值，如 on / 24.5 / cool） */
  private String state;

  /** 面向用户的说明文案 */
  private String message;

  public DeviceTestResultVO() {}

  public DeviceTestResultVO(boolean ok, boolean realHardware, String state, String message) {
    this.ok = ok;
    this.realHardware = realHardware;
    this.state = state;
    this.message = message;
  }

  /** 模拟设备的固定结果。 */
  public static DeviceTestResultVO simulated(String name) {
    return new DeviceTestResultVO(
        true,
        false,
        null,
        "「" + name + "」是本地模拟设备，不连接真实硬件；开关/调温只改变平台显示状态。要测试真实设备，请连接 Home Assistant 中枢。");
  }

  /** 真实设备响应成功。 */
  public static DeviceTestResultVO responded(String state) {
    return new DeviceTestResultVO(true, true, state, "设备响应正常" + (state == null ? "" : "，当前状态：" + state));
  }

  /** 真实设备无响应。 */
  public static DeviceTestResultVO noResponse(String reason) {
    return new DeviceTestResultVO(false, true, null, "设备无响应：" + reason);
  }

  public boolean isOk() {
    return ok;
  }

  public void setOk(boolean ok) {
    this.ok = ok;
  }

  public boolean isRealHardware() {
    return realHardware;
  }

  public void setRealHardware(boolean realHardware) {
    this.realHardware = realHardware;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
