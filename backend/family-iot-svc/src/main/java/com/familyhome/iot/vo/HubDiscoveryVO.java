package com.familyhome.iot.vo;

import lombok.Data;

/**
 * 后台静默发现家庭中枢的结果。
 *
 * <p>重要：本 VO 绝不携带中枢 IP 地址 / 访问令牌。地址与令牌由服务端底层
 * （预置配置或局域网发现模块）自动获取并封装，客户端只需要知道
 * "发现成功 / 未发现"以及面向用户的状态文案。
 */
@Data
public class HubDiscoveryVO {

  /** 是否发现并成功建立连接 */
  private boolean found;

  /** 是否为内置演示中枢（没有真实 Home Assistant 时用于体验） */
  private boolean demo;

  /** 本家庭是否曾部署/绑定过中枢（区分「从未接入」与「接入过但暂时离线」） */
  private boolean provisioned;

  /** 面向用户的状态文案，不含 IP / 域名 / 令牌等敏感信息 */
  private String message;

  public HubDiscoveryVO() {}

  public static HubDiscoveryVO found(boolean demo) {
    HubDiscoveryVO vo = new HubDiscoveryVO();
    vo.setFound(true);
    vo.setDemo(demo);
    vo.setProvisioned(true);
    vo.setMessage("已连接家庭中枢");
    return vo;
  }

  /** 从未接入中枢：需要引导用户部署家庭网关 / 网关盒子并用配对码绑定。 */
  public static HubDiscoveryVO notProvisioned() {
    HubDiscoveryVO vo = new HubDiscoveryVO();
    vo.setFound(false);
    vo.setDemo(false);
    vo.setProvisioned(false);
    vo.setMessage("本家庭尚未绑定中枢：请先在家里部署平台配套网关或接入官方网关盒子，再用家庭配对码完成绑定");
    return vo;
  }

  /** 接入过但当前不可达：网关离线或换网。 */
  public static HubDiscoveryVO offline() {
    HubDiscoveryVO vo = new HubDiscoveryVO();
    vo.setFound(false);
    vo.setProvisioned(true);
    vo.setMessage("请检查中枢是否通电/连接同一WiFi");
    return vo;
  }
}
