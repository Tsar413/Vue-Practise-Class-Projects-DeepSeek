package com.study.vuePractiseBackend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 图片附件视图对象。id 为附件 ID，不是工单图片关联 ID。 */
@Data
public class RepairImageVO {
    private Long id;
    private Long orderImageId;
    private Long orderId;
    private Long processRecordId;
    private Long uploaderId;
    private Integer imageType;
    private Integer status;
    private Integer sortOrder;
    private String originalName;
    private String contentType;
    private Long fileSize;
    /** 相对 /api/practice/{accessCode} 的路径，由前端拼接服务器地址。 */
    private String previewPath;
    private String downloadPath;
    private LocalDateTime createTime;
}
