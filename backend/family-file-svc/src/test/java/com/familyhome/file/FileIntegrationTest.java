package com.familyhome.file;

import static org.assertj.core.api.Assertions.*;

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

    @AfterAll
    static void removeTestFiles() throws Exception {
        try (var paths = java.nio.file.Files.list(ROOT)) {
            for (var path : paths.toList()) java.nio.file.Files.deleteIfExists(path);
        }
        java.nio.file.Files.deleteIfExists(ROOT);
    }
}
