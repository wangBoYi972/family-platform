package com.familyhome.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familyhome.media.dto.MediaSessionCreateDTO;
import com.familyhome.media.service.MediaSessionService;
import com.familyhome.security.SessionPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:media-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("local")
class MediaIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String secret = UUID.randomUUID() + UUID.randomUUID().toString();
        registry.add("FAMILY_JWT_SECRET", () -> secret);
    }

    @Autowired MediaSessionService sessions;

    private final SessionPrincipal creator =
            new SessionPrincipal(11, 101, "MEMBER", "test", "family");
    private final SessionPrincipal householdAdmin =
            new SessionPrincipal(12, 101, "ADMIN", "test", "family");
    private final SessionPrincipal foreignMember =
            new SessionPrincipal(13, 102, "MEMBER", "test", "family");

    @Test
    @DisplayName("媒体会话按家庭隔离，创建者和本家庭管理员才能关闭")
    void enforcesHouseholdIsolationAndCloseAuthorization() {
        var session = sessions.create(creator, new MediaSessionCreateDTO("VIDEO"));

        assertThat(sessions.list(creator)).extracting(item -> item.id()).contains(session.id());
        assertThat(sessions.list(foreignMember))
                .extracting(item -> item.id())
                .doesNotContain(session.id());
        assertThatThrownBy(() -> sessions.close(foreignMember, session.id()))
                .isInstanceOf(ResponseStatusException.class);

        sessions.close(householdAdmin, session.id());

        assertThat(
                        sessions.list(creator).stream()
                                .filter(item -> item.id().equals(session.id()))
                                .findFirst()
                                .orElseThrow()
                                .status())
                .isEqualTo("CLOSED");
    }
}
