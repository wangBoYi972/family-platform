package com.familyhome.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.model.AuthSession;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSession> {
    int countActive(@Param("p") SessionPrincipal principal);

    /**
     * 取数据库时钟。会话有效期写入后要与 countActive 里的 CURRENT_TIMESTAMP 比较，
     * 两边必须同源；用 JVM 时间会在应用与数据库时区不一致时整体偏移。
     */
    LocalDateTime currentTimestamp();
}
