package com.familyhome.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.im.model.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {}
