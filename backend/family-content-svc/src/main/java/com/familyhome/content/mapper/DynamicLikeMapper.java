package com.familyhome.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DynamicLikeMapper {
    Long lock(@Param("id") long id, @Param("household") long household);

    int count(@Param("id") long id, @Param("user") long user);

    void insert(@Param("id") long id, @Param("user") long user);

    void increment(@Param("id") long id, @Param("household") long household);

    void delete(@Param("id") long id);
}
