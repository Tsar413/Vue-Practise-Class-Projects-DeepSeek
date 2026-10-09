package com.study.vuePractiseBackend.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 截图附件视图。
 * 只返回附件 ID 与相对访问路径，不返回服务器绝对路径。
 */
@Data
public class TeachingAttachmentVO {

    private Long id;
    private String originalName;
    private String contentType;
    private Long fileSize;

    /** 相对路径，访问走授权接口 /api/teaching/attachments/{id}/content。 */
    private String imageUrl;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
