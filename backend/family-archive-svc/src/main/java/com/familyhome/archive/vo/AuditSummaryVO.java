package com.familyhome.archive.vo;

import java.util.List;

/**
 * 家庭级审计总览（近 30 天）。聚合在服务端完成——
 * 客户端手里只有它自己可见的档案，按档案逐个拉再拼图既费请求也会漏掉私有档案的访问。
 */
public record AuditSummaryVO(
        long total,
        List<NameCount> byUser,
        List<NameCount> byDay,
        List<NameCount> byAction) {

    /** 某个维度的一行计数；name 是用户 ID / 日期（yyyy-MM-dd）/ 动作。 */
    public record NameCount(String name, long count) {}
}
