package com.familyhome.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.content.model.ReminderSentLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 离线提醒发送记录 Mapper
 */
@Mapper
public interface ReminderSentLogMapper extends BaseMapper<ReminderSentLog> {}
