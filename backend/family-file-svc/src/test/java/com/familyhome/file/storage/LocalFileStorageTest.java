package com.familyhome.file.storage;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {
    @TempDir Path dir;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        var properties = new FileStorageProperties();
        properties.setRoot(dir.toString());
        storage = new LocalFileStorage(properties);
    }

    @Test
    @DisplayName("对象以 UUID 落盘，读取内容一致，删除后不可读")
    void roundTrip() throws IOException {
        String id = UUID.randomUUID().toString();
        byte[] content = "家庭照片二进制".getBytes(StandardCharsets.UTF_8);
        storage.save(id, new ByteArrayInputStream(content), content.length);
        assertThat(Files.exists(dir.resolve(id))).isTrue();
        assertThat(storage.load(id).getContentAsByteArray()).isEqualTo(content);
        storage.delete(id);
        assertThat(Files.exists(dir.resolve(id))).isFalse();
        assertThatThrownBy(() -> storage.load(id)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("不存在的对象读取报错而不是返回空资源")
    void missingObject() {
        assertThatThrownBy(() -> storage.load(UUID.randomUUID().toString()))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("默认类型为 local，s3 凭据默认不填充")
    void defaults() {
        var properties = new FileStorageProperties();
        assertThat(properties.getType()).isEqualTo("local");
        assertThat(properties.getS3().getSecretKey()).isEmpty();
    }
}
