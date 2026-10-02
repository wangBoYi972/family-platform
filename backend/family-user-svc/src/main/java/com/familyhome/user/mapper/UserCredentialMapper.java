package com.familyhome.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.user.model.UserCredential;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserCredentialMapper extends BaseMapper<UserCredential> {
    // 显式写入 NULL，避免实体更新策略保留旧的锁定截止时间。
    int recordAttempt(
            @Param("phone") String phone,
            @Param("attempts") int attempts,
            @Param("until") LocalDateTime until);
}
