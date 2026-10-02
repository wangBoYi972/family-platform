package com.familyhome.user.mapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformInitializationMapper {
    boolean lock();

    int complete();
}
