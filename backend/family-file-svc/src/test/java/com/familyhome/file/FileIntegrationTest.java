package com.familyhome.file;

import static org.assertj.core.api.Assertions.*;

import com.familyhome.common.api.PageQuery;
import com.familyhome.file.dto.FileUploadDTO;
import com.familyhome.file.service.FileService;
import com.familyhome.security.SessionPrincipal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:file-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("local")
class FileIntegrationTest {
    private static final java.nio.file.Path ROOT;

    static {
        try {
            ROOT = java.nio.file.Files.createTempDirectory("family-files-test-");
        } catch (java.io.IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        r.add("FAMILY_JWT_SECRET", () -> secret);
        r.add("FAMILY_FILE_ROOT", () -> ROOT.toString());
    }

    @Autowired FileService service;

    @Test
    @DisplayName("私有文件只能由上传者读取，文件名不能控制存储路径")
    void privateFileAndDeletion() throws Exception {
        var owner = new SessionPrincipal(1, 10, "MEMBER", "test", "family");
        var admin = new SessionPrincipal(2, 10, "ADMIN", "test", "family");
        var stranger = new SessionPrincipal(1, 20, "MEMBER", "test", "family");
        var input =
                new FileUploadDTO(
                        new MockMultipartFile(
                                "file",
                                "../../secret.txt",
                                "text/plain",
                                "测试文件".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "PRIVATE");
        var file = service.upload(owner, input);
        assertThat(file.name()).doesNotContain("/");
        assertThat(service.download(owner, file.id()).resource().getContentAsByteArray())
                .isEqualTo(input.file().getBytes());
        assertThatThrownBy(() -> service.metadata(admin, file.id()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.metadata(stranger, file.id()))
                .isInstanceOf(ResponseStatusException.class);
        service.delete(owner, file.id());
        assertThatThrownBy(() -> service.download(owner, file.id()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("文件列表关键字下推：按文件名或类型匹配，且不越过可见性条件")
    void listKeywordFilter() throws Exception {
        var owner = new SessionPrincipal(1, 10, "MEMBER", "test", "family");
        var other = new SessionPrincipal(3, 10, "MEMBER", "test", "family");
        String tag = "kw-" + UUID.randomUUID().toString().substring(0, 6);
        var bill =
                service.upload(
                        owner,
                        new FileUploadDTO(
                                new MockMultipartFile(
                                        "file", tag + "-账单.pdf", "application/pdf", "x".getBytes()),
                                "FAMILY"));
        var photo =
                service.upload(
                        owner,
                        new FileUploadDTO(
                                new MockMultipartFile("file", tag + "-照片.png", "image/png", "x".getBytes()),
                                "FAMILY"));

        assertThat(service.list(owner, PageQuery.of(1, 50), tag))
                .extracting(x -> x.id())
                .containsExactlyInAnyOrder(bill.id(), photo.id());
        // 只命中文件名之一，另一条不能跟出来
        assertThat(service.list(owner, PageQuery.of(1, 50), tag + "-账单"))
                .extracting(x -> x.id())
                .containsExactly(bill.id());
        // MIME 也参与匹配
        assertThat(service.list(owner, PageQuery.of(1, 50), "image/png"))
                .extracting(x -> x.id())
                .contains(photo.id());
        // 同一家庭的其他成员可见全家文件
        assertThat(service.list(other, PageQuery.of(1, 50), tag))
                .extracting(x -> x.id())
                .containsExactlyInAnyOrder(bill.id(), photo.id());
    }

    @AfterAll
    static void removeTestFiles() throws Exception {
        try (var paths = java.nio.file.Files.list(ROOT)) {
            for (var path : paths.toList()) java.nio.file.Files.deleteIfExists(path);
        }
        java.nio.file.Files.deleteIfExists(ROOT);
    }
}
