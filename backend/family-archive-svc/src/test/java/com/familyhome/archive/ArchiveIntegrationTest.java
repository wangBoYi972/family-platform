package com.familyhome.archive;

import static org.assertj.core.api.Assertions.*;

import com.familyhome.archive.dto.ArchiveSaveDTO;
import com.familyhome.archive.mapper.FamilyArchiveMapper;
import com.familyhome.archive.service.ArchiveService;
import com.familyhome.security.SessionPrincipal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:archive-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("local")
class ArchiveIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        r.add("FAMILY_JWT_SECRET", () -> secret);
        r.add("FAMILY_ARCHIVE_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired ArchiveService service;
    @Autowired FamilyArchiveMapper mapper;

    @Test
    @DisplayName("档案密文入库且同家庭管理员不能读取他人私有档案")
    void encryptionAndPrivacy() {
        var owner = new SessionPrincipal(10, 100, "MEMBER", "test", "family");
        var admin = new SessionPrincipal(11, 100, "ADMIN", "test", "family");
        var stranger = new SessionPrincipal(10, 200, "ADMIN", "test", "family");
        var saved = service.create(owner, new ArchiveSaveDTO("就诊记录", "HEALTH", "受保护的测试正文", "PRIVATE"));
        assertThat(mapper.selectById(saved.id()).getEncryptedBody()).doesNotContain("受保护的测试正文");
        assertThat(service.detail(owner, saved.id()).body()).isEqualTo("受保护的测试正文");
        assertThatThrownBy(() -> service.detail(admin, saved.id()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.detail(stranger, saved.id()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.audit(owner, saved.id()))
                .extracting(a -> a.action())
                .contains("CREATE", "READ");
    }
}
