package com.study.vuePractiseBackend.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 学生成果详情（含全部历史版本与每版评价）。 */
@Data
public class TeachingSubmissionVO {

    private Long id;
    private Long taskId;
    private String studentId;
    private String studentName;
    private String classId;

    private Integer status;
    private Integer versionNo;
    private Boolean late;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /** 草稿内容（尚未正式提交的当前工作副本），可能为空。 */
    private SubmissionVersionVO draft;

    /** 正式版本，按版本号倒序。 */
    private List<SubmissionVersionVO> versions;

    /** 当前生效的评价（对最新版本）。 */
    private EvaluationVO currentEvaluation;
}
