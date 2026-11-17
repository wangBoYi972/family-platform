package com.familyhome.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyhome.ai.dto.ChatRequest;
import com.familyhome.ai.mapper.AiMessageMapper;
import com.familyhome.ai.model.AiMessage;
import com.familyhome.ai.service.AiHistoryService;
import com.familyhome.security.SessionPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 助手历史按人隔离，只留最近 40 条；本机旧历史只在库空时导入一次。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-history;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "family.clients.user-url=http://127.0.0.1:8081",
        "family.clients.file-url=http://127.0.0.1:8087",
        "family.auth.jwt-secret=test-jwt-secret-for-ai-history-32bytes"
})
class AiHistoryServiceTest {

    @Autowired
    private AiHistoryService history;

    @Autowired
    private AiMessageMapper messages;

    @Test
    @DisplayName("只看得到自己的最近 40 条，同家庭其他人和其他家庭都看不到")
    void isolatedAndCapped() {
        var me = new SessionPrincipal(1, 10, "MEMBER", "s", "family");
        var housemate = new SessionPrincipal(2, 10, "MEMBER", "s", "family");
        var sameUserOtherHome = new SessionPrincipal(1, 99, "MEMBER", "s", "family");
        for (int i = 1; i <= 45; i++) history.remember(me, "user", "q" + i);
        history.remember(me, "nope", "非法角色不入库");
        history.remember(me, "assistant", "   ");

        var page = history.recent(me);
        assertThat(page).hasSize(40);
        assertThat(page.getFirst().content()).isEqualTo("q6");
        assertThat(page.getLast().content()).isEqualTo("q45");
        assertThat(history.recent(housemate)).isEmpty();
        assertThat(history.recent(sameUserOtherHome)).isEmpty();
    }

    @Test
    @DisplayName("一次写入前已堆很多行时，prune 会循环删到只剩 40 条")
    void pruneDrainsInBatches() {
        var me = new SessionPrincipal(8, 18, "MEMBER", "s", "family");
        // 绕过 remember，模拟库里一次性堆了 250 行
        for (int i = 1; i <= 250; i++) {
            AiMessage row = new AiMessage();
            row.setHouseholdId(me.householdId());
            row.setUserId(me.userId());
            row.setRole("user");
            row.setContent("bulk-" + i);
            messages.insert(row);
        }
        history.remember(me, "user", "trigger-prune");
        var page = history.recent(me);
        assertThat(page).hasSize(40);
        assertThat(page.getLast().content()).isEqualTo("trigger-prune");
        assertThat(page.getFirst().content()).isEqualTo("bulk-212");
    }

    @Test
    @DisplayName("库是空的才导入本机旧历史，第二次导入不再追加")
    void importOnlyWhenEmpty() {
        var me = new SessionPrincipal(3, 11, "ADMIN", "s", "family");
        var batch = List.of(
                new ChatRequest.Message("user", "以前的问题"),
                new ChatRequest.Message("assistant", "以前的回答"),
                new ChatRequest.Message("system", "这段不该留下来"));
        var imported = history.importIfEmpty(me, batch);
        assertThat(imported).extracting(item -> item.content()).containsExactly("以前的问题", "以前的回答");

        var again = history.importIfEmpty(me, List.of(new ChatRequest.Message("user", "不该再写进去")));
        assertThat(again).extracting(item -> item.content()).containsExactly("以前的问题", "以前的回答");
    }

    @Test
    @DisplayName("同一 clientId 的提问只落库一次，超时重试不会插第二行")
    void rememberIsIdempotentByClientId() {
        var me = new SessionPrincipal(5, 15, "MEMBER", "s", "family");
        String clientId = "11111111-1111-4111-8111-111111111111";
        history.remember(me, "user", "这周有什么任务", clientId);
        history.remember(me, "user", "这周有什么任务", clientId);
        assertThat(history.recent(me)).extracting(item -> item.content())
                .containsExactly("这周有什么任务");
    }

    @Test
    @DisplayName("两路同时空库导入只会留下一份历史")
    void importIfEmptyIsSafeUnderContention() throws Exception {
        var me = new SessionPrincipal(6, 16, "MEMBER", "s", "family");
        var batch = List.of(
                new ChatRequest.Message("user", "设备A的旧问题"),
                new ChatRequest.Message("assistant", "设备A的旧回答"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        try {
            Future<?> first = pool.submit(() -> {
                ready.countDown();
                ready.await(2, TimeUnit.SECONDS);
                history.importIfEmpty(me, batch);
                return null;
            });
            Future<?> second = pool.submit(() -> {
                ready.countDown();
                ready.await(2, TimeUnit.SECONDS);
                history.importIfEmpty(me, batch);
                return null;
            });
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(history.recent(me)).extracting(item -> item.content())
                .containsExactly("设备A的旧问题", "设备A的旧回答");
    }

    @Test
    @DisplayName("流式事件只把 delta 拼进回答，本轮提问取最后一条用户消息")
    void absorbAndLatestQuestion() {
        var buffer = new StringBuilder();
        history.absorb("{\"type\":\"delta\",\"text\":\"你好\"}", buffer);
        history.absorb("{\"type\":\"tool\",\"name\":\"list_tasks\"}", buffer);
        history.absorb("not-json", buffer);
        history.absorb("{\"type\":\"delta\",\"text\":\"，家里\"}", buffer);
        assertThat(buffer.toString()).isEqualTo("你好，家里");

        var messages = new ArrayList<ChatRequest.Message>();
        messages.add(new ChatRequest.Message("user", "更早的问题"));
        messages.add(new ChatRequest.Message("assistant", "更早的回答"));
        messages.add(new ChatRequest.Message("user", "这周有什么任务"));
        assertThat(history.latestUserText(messages)).isEqualTo("这周有什么任务");
    }
}
