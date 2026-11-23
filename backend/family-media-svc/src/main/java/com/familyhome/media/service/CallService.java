package com.familyhome.media.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.media.dto.CallStartDTO;
import com.familyhome.media.vo.CallVO;
import com.familyhome.media.vo.IceServerVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface CallService {
    /** 发起通话，返回会话快照；被叫会通过 WebSocket 收到 call.invited。 */
    CallVO start(SessionPrincipal principal, String bearer, CallStartDTO dto);

    /** 接听：参与者 INVITED -> JOINED，会话 RINGING -> ACTIVE。 */
    CallVO answer(SessionPrincipal principal, String callId);

    void reject(SessionPrincipal principal, String callId);

    void leave(SessionPrincipal principal, String callId);

    /** 挂断整通电话（发起者或管理员）。 */
    void end(SessionPrincipal principal, String callId);

    void updateMediaState(SessionPrincipal principal, String callId, boolean micOn, boolean camOn);

    /**
     * 开关通话录音。录音本身在客户端完成，这里只广播状态：
     * 房间里每个人都要看到「正在录音」，否则就是偷偷录音。
     */
    CallVO setRecording(SessionPrincipal principal, String callId, boolean on);

    CallVO detail(SessionPrincipal principal, String callId);

    /** 当前进行中的通话，没有则返回 null。 */
    CallVO active(SessionPrincipal principal);

    /** 通话记录分页，默认与改造前一致（最近 30 通）。 */
    List<CallVO> history(SessionPrincipal principal, PageQuery page);

    default List<CallVO> history(SessionPrincipal principal) {
        return history(principal, PageQuery.of(null, null, 30));
    }

    /** 信令转发前的成员校验，带短期缓存，避免每帧 ICE 都查库。 */
    boolean canSignal(String callId, long userId);

    List<IceServerVO> iceServers(SessionPrincipal principal);

    /** 回收超时未接听与空房间，返回处理数量。 */
    int sweep();

    /** 断线宽限期结束后自动离开仍在进行的通话。 */
    void leaveIfDisconnected(long userId, long householdId);
}
