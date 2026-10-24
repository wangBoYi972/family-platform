package com.familyhome.content.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/**
 * 任务编辑入参。字段含义与创建一致，但允许把负责人/截止时间置空（取消指派、取消截止日）。
 */
public record TaskUpdateDTO(
        @NotBlank @Size(max = 80) String title, LocalDate deadline, @Positive Long assigneeId) {}
