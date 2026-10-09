package com.study.vuePractiseBackend.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/** 教师评价记录。 */
@Data
public class EvaluationVO {

    private Long id;
    private Long versionId;
    private Integer versionNo;

    /** 实际评分教师，由后端记录。 */
    private String teacherId;
    private String teacherName;

    /** PASS / REVISE。 */
    private String decision;

    private Integer score;
    private String comment;

    /** 是否是对最新版本的当前评价。 */
    private Boolean current;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
