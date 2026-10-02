package com.familyhome.im.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.im.converter.ImConverter;
import com.familyhome.im.dto.*;
import com.familyhome.im.mapper.*;
import com.familyhome.im.model.*;
import com.familyhome.im.service.ImService;
import com.familyhome.im.vo.*;
import com.familyhome.im.websocket.ImSocket;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.messaging.OutboxEventStore;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    public List<ConversationVO> list(SessionPrincipal p) {
        return conversations.selectList(visible(p).orderByDesc(Conversation::getCreatedAt)).stream()
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

    @Transactional(readOnly = true)
    public List<MessageVO> messages(SessionPrincipal p, String id, long after) {
        find(p, id);
        return messages
                .selectList(
                        new LambdaQueryWrapper<ChatMessage>()
                                .eq(ChatMessage::getConversationId, id)
                                .eq(ChatMessage::getHouseholdId, p.householdId())
                                .gt(ChatMessage::getId, Math.max(0, after))
                                .orderByAsc(ChatMessage::getId)
                                .last("LIMIT 100"))
                .stream()
                .map(ImConverter::toVO)
                .toList();
    }

    @Transactional
    public MessageVO send(SessionPrincipal p, String bearer, String id, MessageSendDTO i) {
        find(p, id);
        if (!i.kind().equals("TEXT")) {
            if (!i.body().matches("[a-f0-9-]{36}")) throw new IllegalArgumentException();
            var f = files.metadata(bearer, i.body()).data();
            if (!"FAMILY".equals(f.visibility()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "聊天附件必须为家庭共享文件");
            if ((i.kind().equals("IMAGE") && !f.mime().startsWith("image/"))
                    || (i.kind().equals("AUDIO") && !f.mime().startsWith("audio/")))
                throw new IllegalArgumentException();
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
        socket.notifyFamily(p.householdId());
        outbox.append("message", String.valueOf(m.getId()), "message.sent", p.householdId(),
                Map.of("messageId", m.getId(), "conversationId", id, "senderId", p.userId()));
        return ImConverter.toVO(m);
    }

    @Transactional
    public void read(SessionPrincipal p, String id, MessageReadDTO i) {
        find(p, id);
        if (messages.selectCount(
                        new LambdaQueryWrapper<ChatMessage>()
                                .eq(ChatMessage::getId, i.messageId())
                                .eq(ChatMessage::getConversationId, id))
                != 1) throw new IllegalArgumentException();
        try {
            receipts.insert(id, p.userId());
        } catch (DuplicateKeyException ignored) {
        }
        receipts.advance(id, p.userId(), i.messageId());
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
