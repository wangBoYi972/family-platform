package com.familyhome.im.mapper;

import com.familyhome.im.model.ReadReceipt;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReadReceiptMapper {
    List<ReadReceipt> list(@Param("id") String id);

    long unread(@Param("id") String id, @Param("user") long user);

    void insert(@Param("id") String id, @Param("user") long user);

    /**
     * 把已读水位推到 {@code message}，返回是否真的动了。
     *
     * <p>只进不退（{@code last_message_id < #{message}}）：返回 0 说明这次是原地重复上报，
     * 调用方据此不再推送——否则每开一次会话都给全家发一遍同样的回执。
     */
    int advance(@Param("id") String id, @Param("user") long user, @Param("message") long message);
}
