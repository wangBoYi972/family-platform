package com.familyhome.archive.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 家庭时区：与数据库的 serverTimezone 默认值保持一致（Asia/Shanghai）。
 * 所有「今天」的计算都经过这里，避免服务器所在时区改变后复诊日期漂移。
 */
@Component
public class FamilyClock {
    private final ZoneId zone;

    public FamilyClock(@Value("${family.timezone:Asia/Shanghai}") String zoneId) {
        this.zone = ZoneId.of(zoneId);
    }

    public ZoneId zone() { return zone; }

    public Clock clock() { return Clock.system(zone); }

    public LocalDate today() { return LocalDate.now(clock()); }
}
