package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** 活动发布 / 关闭请求。 */
@Data
public class TicketActivityStatusDTO {

    private Long operatorId;

    /** 1 发布，2 关闭。 */
    private Integer status;
}
