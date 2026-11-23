package com.familyhome.media.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 挂断后提交录音。音频已经分批传到文件服务，这里只登记引用。
 *
 * <p>{@code consent} 是硬性条件：录到的是别人说的话，没有全员知情同意就不该生成纪要，
 * 服务端不提供「先存下来再说」的口子。
 */
public record RecordingSubmitDTO(
        @Size(max = 120) String title,
        @NotNull Boolean consent,
        @Min(0) Integer durationMs,
        @NotEmpty @Size(max = 60) @Valid List<Segment> segments) {

    public record Segment(
            @NotBlank @Size(max = 64) String fileId,
            @Min(0) Integer durationMs) {}
}
