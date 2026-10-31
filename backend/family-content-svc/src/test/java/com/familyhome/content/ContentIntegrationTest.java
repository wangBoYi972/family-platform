package com.familyhome.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.api.file.vo.FileApiVO;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.common.api.PageQuery;
import com.familyhome.content.dto.*;
import com.familyhome.content.service.*;
import com.familyhome.security.SessionPrincipal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired ContentFileMappingService links;
    @Autowired JdbcTemplate jdbc;
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

    @Test
    @DisplayName("文件关联写入 content_file_mapping，更新覆盖、删除清理、旧列保留兼容")
    void contentFileMappingIsSourceOfTruth() {
        String f1 = UUID.randomUUID().toString();
        String f2 = UUID.randomUUID().toString();
        when(files.metadata(anyString(), anyString()))
                .thenReturn(ApiResponse.success(new FileApiVO(f1, 101L, 11L, "a.png", "image/png", 1L, "FAMILY")));

        var dto =
                new EntrySaveDTO(
                        "带图的动态",
                        "正文",
                        java.time.LocalDate.now(),
                        false,
                        false,
                        "FAMILY",
                        f1 + "," + f2);
        var created = entries.create(owner, "unused", "dynamics", dto);

        assertThat(mappingRows("DYNAMIC", created.id())).containsExactly(f1, f2);
        // 旧列同名保留，供未升级的读者回退
        assertThat(jdbc.queryForObject(
                        "SELECT file_ids FROM family_dynamic WHERE id = ?", String.class, created.id()))
                .isEqualTo(f1 + "," + f2);
        // 列表读的是映射表
        assertThat(entries.list(owner, "dynamics").stream()
                        .filter(x -> x.id().equals(created.id()))
                        .findFirst()
                        .orElseThrow()
                        .fileIds())
                .isEqualTo(f1 + "," + f2);

        entries.update(
                owner,
                "unused",
                "dynamics",
                created.id(),
                new EntrySaveDTO("带图的动态", "正文", java.time.LocalDate.now(), false, false, "FAMILY", f2));
        assertThat(mappingRows("DYNAMIC", created.id())).containsExactly(f2);

        entries.delete(owner, "dynamics", created.id());
        assertThat(mappingRows("DYNAMIC", created.id())).isEmpty();
    }

    @Test
    @DisplayName("历史数据只有旧列时由回填补齐，且读取可回退旧列")
    void backfillLegacyFileIds() {
        String legacyFile = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO family_notice(household_id, creator_id, title, body, yearly, pinned, visibility, file_ids, likes, created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                101L, 11L, "历史公告", "正文", false, false, "FAMILY", legacyFile, 0);

        Long id = jdbc.queryForObject(
                "SELECT id FROM family_notice WHERE title = ?", Long.class, "历史公告");
        assertThat(mappingRows("NOTICE", id)).isEmpty();
        // 回填前：读路径回退旧列，功能不中断
        assertThat(entries.list(owner, "notices").stream()
                        .filter(x -> x.id().equals(id))
                        .findFirst()
                        .orElseThrow()
                        .fileIds())
                .isEqualTo(legacyFile);

        assertThat(links.backfillLegacy()).isGreaterThan(0);
        assertThat(mappingRows("NOTICE", id)).containsExactly(legacyFile);
        // 幂等：再跑一次不再产生新行
        int rowsAfter = mappingRows("NOTICE", id).size();
        links.backfillLegacy();
        assertThat(mappingRows("NOTICE", id)).hasSize(rowsAfter);
    }

    private List<String> mappingRows(String contentType, long contentId) {
        return jdbc.queryForList(
                "SELECT file_id FROM content_file_mapping WHERE content_type = ? AND content_id = ? ORDER BY sort_order",
                String.class,
                contentType,
                contentId);
    }

    @Test
    @DisplayName("列表支持分页：不传参数保持原行为，传 page/size 能翻到后面的数据")
    void listPagination() {
        String prefix = "分页任务-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) tasks.create(owner, "unused", new TaskCreateDTO(prefix + "-" + i, null, null));

        // 不传分页参数：等价于改造前，三条都在
        assertThat(tasks.list(owner).stream().filter(t -> t.title().startsWith(prefix)).count())
                .isEqualTo(3);
        // 第一页取 2 条
        var firstPage = tasks.list(owner, PageQuery.of(1, 2));
        assertThat(firstPage).hasSize(2);
        // 第二页接上剩下的 1 条，且与第一页不重叠
        var secondPage = tasks.list(owner, PageQuery.of(2, 2));
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage).extracting(t -> t.id())
                .doesNotContainAnyElementsOf(firstPage.stream().map(t -> t.id()).toList());
        // 非法参数回落：size<=0 用默认值，page<1 当第一页
        assertThat(PageQuery.of(-3, 0).page()).isEqualTo(1);
        assertThat(PageQuery.of(1, 9999).size()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(PageQuery.of(3, 100).offset()).isEqualTo(200L);
    }

    @Test
    @DisplayName("outbox 清理只删保留期外的已发布事件，并留下积压的 PENDING")
    void outboxPurgeOnlyTouchesExpiredPublished() {
        // 三条样本：过期已发布 / 新发布 / 过期仍未发布
        insertOutbox("old-published", "PUBLISHED", 10);
        insertOutbox("new-published", "PUBLISHED", 0);
        insertOutbox("old-pending", "PENDING", 10);

        java.sql.Timestamp before =
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(7)));
        // SQL 与 OutboxMapper.xml 的 purgePublished / countStalePending 保持一致
        int removed =
                jdbc.update(
                        "DELETE FROM event_outbox WHERE id IN ("
                                + "SELECT id FROM (SELECT id FROM event_outbox"
                                + " WHERE status = 'PUBLISHED' AND published_at < ?"
                                + " ORDER BY published_at LIMIT ?) t)",
                        before,
                        500);
        assertThat(removed).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM event_outbox WHERE id = 'new-published'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM event_outbox WHERE status = 'PENDING' AND created_at < ?",
                        Integer.class,
                        before))
                .isEqualTo(1);
    }

    private void insertOutbox(String id, String status, int createdDaysAgo) {        jdbc.update(
                "INSERT INTO event_outbox(id, aggregate_type, aggregate_id, event_type, household_id,"
                        + " payload, status, attempts, next_attempt_at, created_at, published_at)"
                        + " VALUES(?, 'TASK', '1', 'task.created', 101, '{}', ?, 0, ?, ?, ?)",
                id,
                status,
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(createdDaysAgo))),
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(createdDaysAgo))),
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(createdDaysAgo))));
    }
}
