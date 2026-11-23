package com.familyhome.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.iot.model.IotCommandLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 命令日志 Mapper
 */
@Mapper
public interface IotCommandLogMapper extends BaseMapper<IotCommandLog> {
}
