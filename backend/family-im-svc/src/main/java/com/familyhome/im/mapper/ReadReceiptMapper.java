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

    void advance(@Param("id") String id, @Param("user") long user, @Param("message") long message);
}
