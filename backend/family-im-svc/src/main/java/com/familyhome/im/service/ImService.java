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

    /** 单次最多返回的消息条数。 */
    int MESSAGE_PAGE = 100;

    /**
     * 查询消息。
     *
     * @param after 只要 id &gt; after 的消息（增量拉取，正序）
     * @param before 只要 id &lt; before 的消息（加载更早，返回其中最靠后的 limit 条，正序）
     * @param limit 条数上限，&lt;=0 时用 {@link #MESSAGE_PAGE}
     *     <p>after 与 before 都为 0 表示「首次进入会话」，返回**最近** limit 条——
     *     早先这里取的是最早的 100 条，会话一长就永远看不到新消息。
     */
    List<MessageVO> messages(SessionPrincipal p, String id, long after, long before, int limit);

    /** 兼容旧调用：按 after 增量拉取。 */
    default List<MessageVO> messages(SessionPrincipal p, String id, long after) {
        return messages(p, id, after, 0, MESSAGE_PAGE);
    }

    MessageVO send(SessionPrincipal p, String bearer, String id, MessageSendDTO i);

    void read(SessionPrincipal p, String id, MessageReadDTO i);

    List<ReadReceiptVO> reads(SessionPrincipal p, String id);

    SocketTicketVO ticket(SessionPrincipal p);
}
