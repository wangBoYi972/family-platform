package com.familyhome.media.dto;

/** 麦克风/摄像头开关同步；用于在多人通话里展示谁闭麦、谁关摄像头。 */
public record CallStateDTO(boolean mic, boolean cam) {}
