package com.familyhome.content;

import static org.assertj.core.api.Assertions.*;

import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.content.dto.*;
import com.familyhome.content.service.*;
import com.familyhome.security.SessionPrincipal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:content-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("local")
class ContentIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        r.add("FAMILY_JWT_SECRET", () -> secret);
        r.add("FAMILY_ARCHIVE_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired TaskService tasks;
    @Autowired EntryService entries;
    @MockBean UserFeignClient users;
    @MockBean FileFeignClient files;
    private final SessionPrincipal owner = new SessionPrincipal(11, 101, "ADMIN", "test", "family");
    private final SessionPrincipal other = new SessionPrincipal(12, 102, "ADMIN", "test", "family");

    @Test
    @DisplayName("任务写入真实数据库后保持家庭隔离和状态流转规则")
    void taskIsolationAndTransitions() {
        var task = tasks.create(owner, "unused", new TaskCreateDTO("准备周末聚餐", null, null));
        assertThat(tasks.list(owner)).extracting(t -> t.id()).contains(task.id());
        assertThat(tasks.list(other)).extracting(t -> t.id()).doesNotContain(task.id());
        assertThatThrownBy(() -> tasks.status(other, task.id(), "IN_PROGRESS"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tasks.status(owner, task.id(), "COMPLETED"))
                .isInstanceOf(ResponseStatusException.class);
        tasks.status(owner, task.id(), "IN_PROGRESS");
        tasks.status(owner, task.id(), "COMPLETED");
        assertThat(
                        tasks.list(owner).stream()
                                .filter(t -> t.id().equals(task.id()))
                                .findFirst()
                                .orElseThrow()
                                .status())
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("内容映射和点赞事务去重正确，普通成员不可发公告")
    void entryMappersAndLike() {
        var i =
                new EntrySaveDTO("家庭相聚", "测试正文", java.time.LocalDate.now(), false, false, "FAMILY", null);
        var e = entries.create(owner, "unused", "dynamics", i);
        entries.like(owner, "dynamics", e.id());
        entries.like(owner, "dynamics", e.id());
        assertThat(
                        entries.list(owner, "dynamics").stream()
                                .filter(x -> x.id().equals(e.id()))
                                .findFirst()
                                .orElseThrow()
                                .likes())
                .isEqualTo(1);
        assertThat(entries.list(other, "dynamics")).isEmpty();
        for (String kind : List.of("notices", "calendar", "albums")) {
            var created = entries.create(owner, "unused", kind, i);
            assertThat(entries.list(owner, kind)).extracting(x -> x.id()).contains(created.id());
        }
        var member = new SessionPrincipal(13, 101, "MEMBER", "test", "family");
        assertThatThrownBy(() -> entries.create(member, "unused", "notices", i))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("任务可编辑标题/负责人/截止日，且跨家庭不可编辑")
    void taskUpdate() {
        var task = tasks.create(owner, "unused", new TaskCreateDTO("买菜", null, null));
        var updated =
                tasks.update(
                        owner,
                        "unused",
                        task.id(),
                        new TaskUpdateDTO("周末买菜", java.time.LocalDate.of(2026, 12, 1), null));
        assertThat(updated.title()).isEqualTo("周末买菜");
        assertThat(updated.deadline()).isEqualTo(java.time.LocalDate.of(2026, 12, 1));
        assertThat(
                        tasks.list(owner).stream()
                                .filter(t -> t.id().equals(task.id()))
                                .findFirst()
                                .orElseThrow()
                                .title())
                .isEqualTo("周末买菜");
        assertThatThrownBy(
                        () ->
                                tasks.update(
                                        other, "unused", task.id(), new TaskUpdateDTO("改别人的", null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("任务状态可回退一步，但不能跳跃")
    void taskStatusRollback() {
        var task = tasks.create(owner, "unused", new TaskCreateDTO("打扫卫生", null, null));
        // 跳跃：TODO 直接到 COMPLETED 不允许
        assertThatThrownBy(() -> tasks.status(owner, task.id(), "COMPLETED"))
                .isInstanceOf(ResponseStatusException.class);
        tasks.status(owner, task.id(), "IN_PROGRESS");
        // 回退：IN_PROGRESS -> TODO
        tasks.status(owner, task.id(), "TODO");
        assertThat(statusOf(owner, task.id())).isEqualTo("TODO");
        tasks.status(owner, task.id(), "IN_PROGRESS");
        tasks.status(owner, task.id(), "COMPLETED");
        // 重开：COMPLETED -> IN_PROGRESS
        tasks.status(owner, task.id(), "IN_PROGRESS");
        assertThat(statusOf(owner, task.id())).isEqualTo("IN_PROGRESS");
    }

    private String statusOf(SessionPrincipal p, long id) {
        return tasks.list(p).stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow()
                .status();
    }
}
