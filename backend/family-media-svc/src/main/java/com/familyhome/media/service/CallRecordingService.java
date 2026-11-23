package com.familyhome.media.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.media.dto.RecordingSubmitDTO;
import com.familyhome.media.vo.CallRecordingVO;
import com.familyhome.security.SessionPrincipal;

import java.util.List;

/** 通话录音登记与纪要查询。转写在后台线程完成，接口只负责登记与读状态。 */
public interface CallRecordingService {

    /** 挂断后提交录音（音频已上传到文件服务）。 */
    CallRecordingVO submit(SessionPrincipal principal, String bearer, String callId, RecordingSubmitDTO input);

    /** 某通电话的纪要；没有提交过录音时返回 {@code null}，前端据此显示「未录音」。 */
    CallRecordingVO ofCall(SessionPrincipal principal, String callId);

    CallRecordingVO detail(SessionPrincipal principal, String id);

    /** 家庭内的纪要列表，不含正文。 */
    List<CallRecordingVO> list(SessionPrincipal principal, PageQuery page);

    /** 失败后重新排队。成功或进行中的记录不允许重试，避免重复计费。 */
    CallRecordingVO retry(SessionPrincipal principal, String bearer, String id);
}
