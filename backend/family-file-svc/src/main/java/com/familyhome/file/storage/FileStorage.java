package com.familyhome.file.storage;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * 文件对象存储抽象：屏蔽本地磁盘与 S3 兼容对象存储的差异。
 * 上传/下载/删除均以业务对象 id（UUID）寻址，禁止把用户文件名拼入物理路径。
 */
public interface FileStorage {
    void save(String id, InputStream content, long size) throws IOException;

    Resource load(String id) throws IOException;

    void delete(String id) throws IOException;
}
