package com.familyhome.file.vo;

import org.springframework.core.io.Resource;

public record FileDownloadVO(String name, Resource resource) {}
