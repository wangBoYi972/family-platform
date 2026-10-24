package com.familyhome.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.user.model.InviteCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InviteCodeMapper extends BaseMapper<InviteCode> {
    /** 行锁读取邀请码，保证同一邀请码的并发注册不会超额使用。 */
    InviteCode lockByHash(@Param("codeHash") String codeHash);
}
