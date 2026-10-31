package com.familyhome.archive;

import static org.assertj.core.api.Assertions.*;

import com.familyhome.archive.dto.ArchiveSaveDTO;
import com.familyhome.common.api.PageQuery;
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

    @Test
    @DisplayName("密钥更换后旧档案读取返回明确业务错误，而不是 500")
    void undecryptableArchiveIsNotServerError() {
        var owner = new SessionPrincipal(10, 100, "MEMBER", "test", "family");
        var saved = service.create(owner, new ArchiveSaveDTO("旧档案", "DOCUMENT", "旧密钥加密的正文", "PRIVATE"));
        // 模拟密钥轮换：用另一把密钥重新加密同一段正文，写回库里
        byte[] otherKey = new byte[32];
        Arrays.fill(otherKey, (byte) 7);
        var stale = new com.familyhome.archive.security.ArchiveCipher(
                Base64.getEncoder().encodeToString(otherKey));
        var row = mapper.selectById(saved.id());
        row.setEncryptedBody(stale.encrypt("旧密钥加密的正文", "100:" + saved.id()));
        mapper.updateById(row);

        var thrown = catchThrowable(() -> service.detail(owner, saved.id()));
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        // 必须是有明确语义的 410，冒泡成 500 会让前端只能显示「服务暂时不可用」
        assertThat(((ResponseStatusException) thrown).getStatusCode().value()).isEqualTo(410);
    }

    @Test
    @DisplayName("档案筛选下推：关键字只查标题，分类精确匹配，私有档案不越过可见性")
    void listFilterIsPushedDown() {
        var owner = new SessionPrincipal(20, 300, "MEMBER", "test", "family");
        var mate = new SessionPrincipal(21, 300, "MEMBER", "test", "family");
        String tag = "筛选-" + UUID.randomUUID().toString().substring(0, 6);
        var health = service.create(owner, new ArchiveSaveDTO(tag + "-体检报告", "HEALTH", "正文A", "FAMILY"));
        var doc = service.create(owner, new ArchiveSaveDTO(tag + "-房产证", "DOCUMENT", "正文B", "FAMILY"));
        var secret = service.create(owner, new ArchiveSaveDTO(tag + "-私密记录", "HEALTH", "正文C", "PRIVATE"));

        assertThat(service.list(owner, PageQuery.of(1, 50), tag, null))
                .extracting(x -> x.id())
                .containsExactlyInAnyOrder(health.id(), doc.id(), secret.id());
        assertThat(service.list(owner, PageQuery.of(1, 50), tag, "DOCUMENT"))
                .extracting(x -> x.id())
                .containsExactly(doc.id());
        assertThat(service.list(owner, PageQuery.of(1, 50), tag, "HEALTH"))
                .extracting(x -> x.id())
                .containsExactlyInAnyOrder(health.id(), secret.id());
        // 关键字只匹配标题：正文里的字搜不到（正文是密文，列表也不返回）
        assertThat(service.list(owner, PageQuery.of(1, 50), "正文A", null)).isEmpty();
        // 同一家庭的其他人看不到私有档案，即使关键字命中
        assertThat(service.list(mate, PageQuery.of(1, 50), tag, null))
                .extracting(x -> x.id())
                .containsExactlyInAnyOrder(health.id(), doc.id());
    }
}
