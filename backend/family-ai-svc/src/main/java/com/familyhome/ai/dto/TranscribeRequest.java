package com.familyhome.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 一段通话录音的转写请求。
 *
 * <p>只传文件标识，不传音频本体：音频始终存在私有文件服务里，由本服务用调用方的令牌
 * 取回后转成 base64 交给模型，避免为了转写把家庭录音暴露到公网 URL 上。
 */
public record TranscribeRequest(
        @NotBlank @Size(max = 64) String fileId,
        /** 客户端已知的 MIME，可为空；为空时按文件头嗅探。 */
        @Size(max = 64) String mime,
        /** 该段录音时长，仅用于日志与提示，不参与计费判断。 */
        Integer durationMs) {

    public TranscribeRequest(String fileId) {
        this(fileId, null, null);
    }

    @Override
    public String toString() {
        return "TranscribeRequest[fileId=" + fileId + ", mime=" + mime + "]";
    }
}
