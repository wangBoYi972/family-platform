package com.familyhome.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.model.AuthSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSession> {
    int countActive(@Param("p") SessionPrincipal principal);
}
