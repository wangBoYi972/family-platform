package com.familyhome.user.vo;

/**
 * 订阅消息发送结果。
 *
 * <p>sent=已发出；skipped=未配置/openid 缺失/非同家庭；no-quota=没有可用授权；
 * failed=微信接口失败（配额已消耗，下次要重新授权）。调用方只读不打断。
 */
public record WxNotifyResultVO(String result) {}
