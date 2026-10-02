package com.familyhome.file.storage;

import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** 本地磁盘存储：对象以 UUID 为文件名，目录在启动时创建并校验存在性。 */
@Component
@EnableConfigurationProperties(FileStorageProperties.class)
@ConditionalOnProperty(name = "family.file.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {
    @Autowired private FileStorageProperties properties;
    private Path root;

    public LocalFileStorage() {
    }

    /** 保留手工实例化入口，兼容不启动 Spring 的存储单元测试。 */
    public LocalFileStorage(FileStorageProperties properties) {
        this.properties = properties;
        this.root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() throws IOException {
        root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public void save(String id, InputStream content, long size) throws IOException {
        Files.copy(content, root.resolve(id), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public Resource load(String id) throws IOException {
        Path target = root.resolve(id);
        if (!Files.isRegularFile(target)) throw new IOException("对象不存在: " + id);
        return new FileSystemResource(target);
    }

    @Override
    public void delete(String id) throws IOException {
        Files.deleteIfExists(root.resolve(id));
    }
}
