package com.study.vuePractiseBackend.dto;

import lombok.Data;

/** 任务流转入参：发布 / 关闭。 */
@Data
public class TeachingStatusDTO {
    /** PUBLISH 或 CLOSE。 */
    private String action;
}
