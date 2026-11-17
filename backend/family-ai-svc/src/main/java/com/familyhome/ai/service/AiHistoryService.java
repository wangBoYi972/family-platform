package com.familyhome.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.ai.dto.AiHistoryMessage;
import com.familyhome.ai.dto.ChatRequest;
import com.familyhome.ai.mapper.AiChatClientMapper;
import com.familyhome.ai.mapper.AiMessageMapper;
import com.familyhome.ai.mapper.AiOwnerLockMapper;
import com.familyhome.ai.model.AiChatClient;
import com.familyhome.ai.model.AiMessage;
import com.familyhome.ai.model.AiOwnerLock;
import com.familyhome.security.SessionPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 小助手对话历史。按「当前登录用户 + 当前家庭」隔离，换设备打开同一账号能接着看。
 * 只留最近 40 条，和客户端原来的本地上限一致。
 */
@Service
public class AiHistoryService {

    static final int LIMIT = 40;
    private static final int MAX_CONTENT = 8000;
    private static final int PRUNE_BATCH = 100;

    @Autowired
    private AiMessageMapper messages;

    @Autowired
    private AiChatClientMapper chatClients;

    @Autowired
    private AiOwnerLockMapper ownerLocks;

    private final ObjectMapper json = new ObjectMapper();

    /** 当前用户最近的历史，时间正序，方便直接铺到气泡上。 */
    public List<AiHistoryMessage> recent(SessionPrincipal principal) {
        List<AiMessage> latest = messages.selectList(owner(principal)
                .orderByDesc(AiMessage::getId)
                .last("LIMIT " + LIMIT));
        Collections.reverse(latest);
        return latest.stream().map(this::toView).toList();
    }

    /**
     * 记下一条发言。空白、超长和非法角色直接丢掉，不让脏数据进库。
     * 助手回答为空（模型只报了错）时调用方也会走到这里，这里负责当成没发生。
     * 带 clientId 的用户提问：同一键只插一次，超时重试不会变成两行。
     */
    public void remember(SessionPrincipal principal, String role, String content) {
        remember(principal, role, content, null);
    }

    public void remember(SessionPrincipal principal, String role, String content, String clientId) {
        String text = normalize(role, content);
        if (text == null) return;
        String idempotencyKey = normalizeClientId(clientId);
        if (idempotencyKey != null && !claimClient(principal, idempotencyKey)) return;

        AiMessage row = new AiMessage();
        row.setHouseholdId(principal.householdId());
        row.setUserId(principal.userId());
        row.setRole(role);
        row.setContent(text);
        row.setClientId(idempotencyKey);
        messages.insert(row);
        prune(principal);
    }

    /** 库里只留最近 40 条。循环删，避免一次堆很多时要写好几轮才收干净。 */
    private void prune(SessionPrincipal principal) {
        while (true) {
            List<AiMessage> extra = messages.selectList(owner(principal)
                    .orderByDesc(AiMessage::getId)
                    .last("LIMIT " + PRUNE_BATCH + " OFFSET " + LIMIT));
            if (extra.isEmpty()) return;
            messages.delete(new LambdaQueryWrapper<AiMessage>()
                    .in(AiMessage::getId, extra.stream().map(AiMessage::getId).toList()));
            if (extra.size() < PRUNE_BATCH) return;
        }
    }

    /**
     * 本机旧历史上传。库里已经有记录就原样返回。
     * 先占住 ai_owner_lock 再查空，避免两台设备同时看到空库各写一份。
     */
    @Transactional
    public List<AiHistoryMessage> importIfEmpty(SessionPrincipal principal, List<ChatRequest.Message> batch) {
        lockOwner(principal);
        if (messages.selectCount(owner(principal)) > 0) return recent(principal);
        if (batch != null) {
            int kept = 0;
            for (ChatRequest.Message item : batch) {
                if (item == null || kept >= LIMIT) continue;
                String text = normalize(item.role(), item.content());
                if (text == null) continue;
                remember(principal, item.role(), text);
                kept++;
            }
        }
        return recent(principal);
    }

    /** 本轮请求里用户真正发出的那一句（历史列表的最后一条 user）。 */
    public String latestUserText(List<ChatRequest.Message> batch) {
        if (batch == null) return null;
        for (int i = batch.size() - 1; i >= 0; i--) {
            ChatRequest.Message item = batch.get(i);
            if (item == null || !"user".equals(item.role())) continue;
            String text = normalize("user", item.content());
            if (text != null) return text;
        }
        return null;
    }

    /** 从一条 SSE 事件 JSON 里把 delta 文本拼进回答。解析失败就跳过，不打断流。 */
    public void absorb(String line, StringBuilder reply) {
        if (line == null || line.isBlank() || reply == null) return;
        try {
            JsonNode node = json.readTree(line);
            if ("delta".equals(node.path("type").asText())) reply.append(node.path("text").asText(""));
        } catch (JsonProcessingException ignored) {
            // 半截或非 JSON 事件不影响已经推给用户的正文
        }
    }

    private void lockOwner(SessionPrincipal principal) {
        AiOwnerLock row = new AiOwnerLock();
        row.setHouseholdId(principal.householdId());
        row.setUserId(principal.userId());
        try {
            ownerLocks.insert(row);
        } catch (DuplicateKeyException ignored) {
            // 已有占位行，下面 FOR UPDATE 即可
        }
        ownerLocks.lockForUpdate(principal.householdId(), principal.userId());
    }

    /** @return true 表示抢到这次提问；false 表示同一 clientId 已经落过库。 */
    private boolean claimClient(SessionPrincipal principal, String clientId) {
        AiChatClient row = new AiChatClient();
        row.setHouseholdId(principal.householdId());
        row.setUserId(principal.userId());
        row.setClientId(clientId);
        try {
            chatClients.insert(row);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    private LambdaQueryWrapper<AiMessage> owner(SessionPrincipal principal) {
        return new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getHouseholdId, principal.householdId())
                .eq(AiMessage::getUserId, principal.userId());
    }

    private AiHistoryMessage toView(AiMessage row) {
        return new AiHistoryMessage(row.getId(), row.getRole(), row.getContent(), row.getCreatedAt());
    }

    /** 只接受 user / assistant；空白丢弃；超长截断，和请求体上限对齐。 */
    private String normalize(String role, String content) {
        if (!"user".equals(role) && !"assistant".equals(role)) return null;
        if (content == null) return null;
        String text = content.trim();
        if (text.isEmpty()) return null;
        return text.length() > MAX_CONTENT ? text.substring(0, MAX_CONTENT) : text;
    }

    private String normalizeClientId(String clientId) {
        if (clientId == null) return null;
        String text = clientId.trim();
        if (text.isEmpty() || text.length() > 36) return null;
        return text;
    }
}
