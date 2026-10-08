package com.study.vuePractiseBackend.vo;

import org.springframework.core.io.Resource;

/** 图片响应内容，不作为 JSON 返回。 */
public record RepairImageResource(Resource resource, String contentType, String originalName) {
}
