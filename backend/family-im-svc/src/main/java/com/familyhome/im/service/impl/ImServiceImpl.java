package com.familyhome.im.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.api.user.service.MemberCacheService;
import com.familyhome.api.user.vo.WxNotifyApiVO;
import com.familyhome.common.api.PageQuery;
import com.familyhome.im.converter.ImConverter;
import com.familyhome.im.dto.*;
import com.familyhome.im.mapper.*;
import com.familyhome.im.model.*;
import com.familyhome.im.service.ImService;
import com.familyhome.im.vo.*;
import com.familyhome.im.websocket.ImSocket;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.messaging.OutboxEventStore;
import com.familyhome.messaging.RedisOnlineConnectionRegistry;

import java.time.LocalDateTime;
import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class ImServiceImpl implements ImService {

    @Autowired
    private ConversationMapper conversations;
    @Autowired
    private ChatMessageMapper messages;
    @Autowired
    private ReadReceiptMapper receipts;
    @Autowired
    private UserFeignClient users;
    @Autowired
    private FileFeignClient files;
    @Autowired
    private ImSocket socket;
    @Autowired
    private OutboxEventStore outbox;
    /** 按用户的在线标记：只有真不在线的家人才值得动用微信服务通知。 */
    @Autowired
    private ObjectProvider<RedisOnlineConnectionRegistry> onlineRegistry;
    @Autowired
    private MemberCacheService memberCache;

    private LambdaQueryWrapper<Conversation> visible(SessionPrincipal p) {
        return new LambdaQueryWrapper<Conversation>()
            .eq(Conversation::getHouseholdId, p.householdId())
            .and(
                q ->
                    q.eq(Conversation::getKind, "GROUP")
                        .or()
                        .eq(Conversation::getUserA, p.userId())
                        .or()
                        .eq(Conversation::getUserB, p.userId()));
    }

    private Conversation find(SessionPrincipal p, String id) {
        var c = conversations.selectOne(visible(p).eq(Conversation::getId, id));
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或未加入");
        return c;
    }

    @Transactional(readOnly = true)
    public List<ConversationVO> list(SessionPrincipal p, PageQuery page) {
        return conversations
            .selectList(
                visible(p).orderByDesc(Conversation::getCreatedAt).last(page.limitClause()))
            .stream()
            .map(c -> ImConverter.toVO(c, receipts.unread(c.getId(), p.userId())))
            .toList();
    }

    @Transactional
    public ConversationVO create(SessionPrincipal p, String bearer, ConversationCreateDTO i) {
        Long peer = i.peerId();
        if (peer != null
            && (peer == p.userId()
            || users.members(bearer).data().stream().noneMatch(m -> Objects.equals(m.id(), peer))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择本家庭的其他成员");
        String id =
            peer == null
                ? "group-" + p.householdId()
                : "dm-"
                + p.householdId()
                + "-"
                + Math.min(peer, p.userId())
                + "-"
                + Math.max(peer, p.userId());
        var c = new Conversation();
        c.setId(id);
        c.setHouseholdId(p.householdId());
        c.setKind(peer == null ? "GROUP" : "DM");
        c.setUserA(p.userId());
        c.setUserB(peer);
        c.setTitle(peer == null ? "家庭群聊" : "家庭私聊");
        c.setCreatedAt(LocalDateTime.now());
        try {
            conversations.insert(c);
        } catch (DuplicateKeyException ignored) {
        }
        return detail(p, id);
    }

    @Transactional(readOnly = true)
    public ConversationVO detail(SessionPrincipal p, String id) {
        return ImConverter.toVO(find(p, id), receipts.unread(id, p.userId()));
    }

    /** 单次上限，超过就按这个切；前端「加载更早」依赖这个值与返回条数比较判断是否还有。 */
    private static final int MAX_MESSAGE_PAGE = 200;

    @Transactional(readOnly = true)
    public List<MessageVO> messages(
            SessionPrincipal p, String id, long after, long before, int limit) {
        find(p, id);
        int size = limit <= 0 ? ImService.MESSAGE_PAGE : Math.min(limit, MAX_MESSAGE_PAGE);
        LambdaQueryWrapper<ChatMessage> query =
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, id)
                        .eq(ChatMessage::getHouseholdId, p.householdId());
        if (after > 0) {
            // 增量：游标之后的消息按时间正序取最早的一批，前端靠最后一条的 id 继续追。
            return messages
                    .selectList(
                            query.gt(ChatMessage::getId, after)
                                    .orderByAsc(ChatMessage::getId)
                                    .last("LIMIT " + size))
                    .stream()
                    .map(ImConverter::toVO)
                    .toList();
        }
        // 首次进入或「加载更早」：先按倒序取最近的 size 条，再翻回正序。
        // 早先这里用正序 + LIMIT 100，等于只给最早的 100 条，会话一长新消息就永远不出现。
        if (before > 0) query.lt(ChatMessage::getId, before);
        List<ChatMessage> rows =
                messages.selectList(query.orderByDesc(ChatMessage::getId).last("LIMIT " + size));
        Collections.reverse(rows);
        return rows.stream().map(ImConverter::toVO).toList();
    }

    @Transactional
    public MessageVO send(SessionPrincipal p, String bearer, String id, MessageSendDTO i) {
        Conversation c = find(p, id);
        if (!i.kind().equals("TEXT")) {
            if (!i.body().matches("[a-f0-9-]{36}"))
                throw new IllegalArgumentException("无效的文件 ID 格式");
            var f = files.metadata(bearer, i.body()).data();
            if (!"FAMILY".equals(f.visibility()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "聊天附件必须为家庭共享文件");
            if ((i.kind().equals("IMAGE") && !f.mime().startsWith("image/"))
                || (i.kind().equals("AUDIO") && !f.mime().startsWith("audio/")))
                throw new IllegalArgumentException("文件类型与消息类型不匹配");
        }
        var m = new ChatMessage();
        m.setConversationId(id);
        m.setHouseholdId(p.householdId());
        m.setSenderId(p.userId());
        m.setClientId(i.clientId());
        m.setKind(i.kind());
        m.setBody(i.body());
        m.setCreatedAt(LocalDateTime.now());
        try {
            messages.insert(m);
        } catch (DuplicateKeyException ex) {
            m =
                messages.selectOne(
                    new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSenderId, p.userId())
                        .eq(ChatMessage::getClientId, i.clientId()));
            if (m == null
                || !id.equals(m.getConversationId())
                || !i.body().equals(m.getBody())
                || !i.kind().equals(m.getKind()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "重复请求编号对应了不同消息");
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "message");
        frame.put("conversationId", id);
        frame.put("messageId", m.getId());
        frame.put("senderId", m.getSenderId());
        frame.put("clientId", m.getClientId());
        frame.put("kind", m.getKind());
        frame.put("body", m.getBody());
        frame.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
        pushAfterCommit(p.householdId(), frame, audience(c));
        notifyWxAfterCommit(bearer, p, c, m);
        outbox.append("message", String.valueOf(m.getId()), "message.sent", p.householdId(),
            Map.of("messageId", m.getId(), "conversationId", id, "senderId", p.userId()));
        return ImConverter.toVO(m);
    }

    /**
     * 这条会话的帧该投给谁。{@code null} = 整户广播（群聊）。
     *
     * <p>私聊只给双方：帧是明文正文，广播给全家庭等于「客户端不显示但服务端已经送到了」。
     */
    private static List<Long> audience(Conversation c) {
        if ("GROUP".equals(c.getKind()) || c.getUserA() == null || c.getUserB() == null) return null;
        return List.of(c.getUserA(), c.getUserB());
    }

    @Transactional
    public void recall(SessionPrincipal p, String id, long messageId) {
        Conversation c = find(p, id);
        ChatMessage m =
            messages.selectOne(
                new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getId, messageId)
                    .eq(ChatMessage::getConversationId, id));
        if (m == null) throw new IllegalArgumentException("消息不存在");
        if (!Objects.equals(m.getSenderId(), p.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能撤回自己发出的消息");
        if (Boolean.TRUE.equals(m.getDeleted())) return; // 重复撤回按成功处理，客户端不必区分
        // 服务端时间做权威判断；用 createdAt + 窗口 与 now 比较，别反过来 now - 窗口，
        // 那样对 createdAt 为 null 的旧数据会误判成「还没到窗口」
        if (m.getCreatedAt() == null
            || m.getCreatedAt().plusSeconds(RECALL_WINDOW_SECONDS).isBefore(LocalDateTime.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "超过可撤回时间");
        // 正文一并清掉：软删只是占位用的，原文本不该继续躺在接口响应里
        m.setDeleted(true);
        m.setBody("");
        messages.updateById(m);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "message.deleted");
        frame.put("conversationId", id);
        frame.put("messageId", messageId);
        pushAfterCommit(p.householdId(), frame, audience(c));
        outbox.append("message", String.valueOf(messageId), "message.deleted", p.householdId(),
            Map.of("messageId", messageId, "conversationId", id, "senderId", m.getSenderId()));
    }

    /**
     * 把新消息推给该看到它的连接。
     *
     * <p>必须等事务提交：提前推送会把一条最终回滚掉的消息显示在家人屏幕上。
     * 帧里带齐了客户端渲染要用的字段，收到即可上屏，不用再回查接口。
     */
    private void pushAfterCommit(long household, Map<String, Object> frame, List<Long> audience) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            socket.notifyFamilyEvent(household, frame, audience);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        socket.notifyFamilyEvent(household, frame, audience);
                    }
                });
    }

    /**
     * 给「不在线」的收件人补一条微信服务通知（消耗对方授权过的订阅配额）。
     *
     * <p>只盯私聊/群聊里真正不在的人：人就在应用里，实时帧已经秒到了，再弹服务通知是打扰。
     * 发送方名字和正文预览由 user-svc 翻译（它管用户数据，也管模板字段映射），
     * 这里只递语义字段。整条链路 try/catch 兜底——服务通知是锦上添花，绝不影响发消息主流程。
     */
    private void notifyWxAfterCommit(String bearer, SessionPrincipal sender, Conversation c, ChatMessage m) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            pushWx(bearer, sender, c, m);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        pushWx(bearer, sender, c, m);
                    }
                });
    }

    private void pushWx(String bearer, SessionPrincipal sender, Conversation c, ChatMessage m) {
        try {
            RedisOnlineConnectionRegistry registry = onlineRegistry.getIfAvailable();
            if (registry == null) return;
            List<Long> recipients =
                audience(c) == null
                    ? memberCache.getMembers(bearer, sender.householdId()).stream()
                        .map(mm -> mm.id())
                        .filter(id -> !id.equals(sender.userId()))
                        .toList()
                    : audience(c).stream().filter(id -> !id.equals(sender.userId())).toList();
            Map<String, Object> data = Map.of("sender", sender.userId(), "preview", preview(m));
            for (Long uid : recipients) {
                if (registry.isUserOnline(uid)) continue;
                users.wxNotify(bearer, new WxNotifyApiVO(uid, "im-message", null, data));
            }
        } catch (Exception ex) {
            log.debug("离线订阅消息跳过：{}", ex.getMessage());
        }
    }

    /** 预览截断在 user-svc 做（它知道微信字段的长度上限），这里只砍掉超长正文省带宽。 */
    private static String preview(ChatMessage m) {
        String body = m.getBody() == null ? "" : m.getBody();
        return body.length() > 60 ? body.substring(0, 60) : body;
    }

    @Transactional
    public void read(SessionPrincipal p, String id, MessageReadDTO i) {
        Conversation c = find(p, id);
        if (!Long.valueOf(1L).equals(messages.selectCount(
            new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getId, i.messageId())
                .eq(ChatMessage::getConversationId, id))))
            throw new IllegalArgumentException("消息 ID 无效");
        try {
            receipts.insert(id, p.userId());
        } catch (DuplicateKeyException ignored) {
        }
        if (receipts.advance(id, p.userId(), i.messageId()) <= 0) return;
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "read");
        frame.put("conversationId", id);
        frame.put("userId", p.userId());
        frame.put("lastMessageId", i.messageId());
        pushAfterCommit(p.householdId(), frame, audience(c));
    }

    @Transactional(readOnly = true)
    public List<ReadReceiptVO> reads(SessionPrincipal p, String id) {
        find(p, id);
        return receipts.list(id).stream().map(ImConverter::toVO).toList();
    }

    public SocketTicketVO ticket(SessionPrincipal p) {
        return new SocketTicketVO(socket.ticket(p));
    }
}
