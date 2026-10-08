package com.study.vuePractiseBackend.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 抢票活动新建 / 修改请求，两者字段一致。 */
@Data
public class TicketActivityDTO {
    /** 操作人：必须是当前空间内启用状态的模拟管理员。 */
    private Long operatorId;
    private String activityName;
    private String description;
    private String coverUrl;
    private String campus;
    private String location;
    private Integer quota;
    private LocalDateTime bookingStartTime;
    private LocalDateTime bookingEndTime;
    private LocalDateTime activityStartTime;
    private LocalDateTime activityEndTime;
}
