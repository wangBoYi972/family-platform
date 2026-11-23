package com.familyhome.ai.dto;

/** 纪要正文（Markdown），前端按纯文本渲染即可，不依赖富文本解析。 */
public record MinutesVO(String minutes, String model) {}
