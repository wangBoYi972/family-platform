package com.familyhome.im.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.im.dto.*;
import com.familyhome.im.vo.*;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface ImService {
    /** 分页查询；不传分页参数时等价于改造前的「取前 500 条」。 */
    List<ConversationVO> list(SessionPrincipal p, PageQuery page);

    default List<ConversationVO> list(SessionPrincipal p) {
        return list(p, PageQuery.first());
    }

    ConversationVO create(SessionPrincipal p, String bearer, ConversationCreateDTO i);

    ConversationVO detail(SessionPrincipal p, String id);

    List<MessageVO> messages(SessionPrincipal p, String id, long after);

    MessageVO send(SessionPrincipal p, String bearer, String id, MessageSendDTO i);

    void read(SessionPrincipal p, String id, MessageReadDTO i);

    List<ReadReceiptVO> reads(SessionPrincipal p, String id);

    SocketTicketVO ticket(SessionPrincipal p);
}
