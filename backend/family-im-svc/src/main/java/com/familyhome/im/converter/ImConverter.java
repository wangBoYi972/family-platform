package com.familyhome.im.converter;

import com.familyhome.im.model.*;
import com.familyhome.im.vo.*;

public final class ImConverter {
    private ImConverter() {
    }

    public static ConversationVO toVO(Conversation c, long unread) {
        return new ConversationVO(
            c.getId(), c.getKind(), c.getUserA(), c.getUserB(), c.getTitle(), unread, c.getCreatedAt());
    }

    public static MessageVO toVO(ChatMessage m) {
        return new MessageVO(
            m.getId(),
            m.getConversationId(),
            m.getSenderId(),
            m.getClientId(),
            m.getKind(),
            m.getBody(),
            m.getCreatedAt(),
            Boolean.TRUE.equals(m.getDeleted()));
    }

    public static ReadReceiptVO toVO(ReadReceipt r) {
        return new ReadReceiptVO(r.userId(), r.lastMessageId());
    }
}
