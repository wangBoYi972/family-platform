package com.familyhome.user.config;

import com.familyhome.user.mapper.AuthSessionMapper;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 统一取「当前时间」。
 *
 * <p>背景：会话过期时间留过一次隐患——写入用 {@code ZoneId.systemDefault()}，判定却用数据库的
 * {@code CURRENT_TIMESTAMP}。本地开发两边都是 +08:00 看不出问题；一旦容器化后应用与 MySQL
 * 时区不同，会话就会整体提前或延后若干小时，表现为「刚登录就掉线」。
 *
 * <p>凡是「写入后要交给 SQL 比较」的字段（{@code auth_session.expires_at}、
 * {@code invite_code.expires_at}），一律用它取时间，保证两侧同源。
 */
@Component
public class DbClock {
    @Autowired private AuthSessionMapper sessions;

    public LocalDateTime now() {
        return sessions.currentTimestamp();
    }
}
