package com.study.vuePractiseBackend.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 一个正式提交版本（含其章节截图与该版本收到的评价）。 */
@Data
public class SubmissionVersionVO {

    private Long id;
    private Integer versionNo;
    private String projectUrl;
    private String content;
    private String process;
    private Boolean late;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private List<SubmissionSectionVO> sections;

    /** 该版本收到的评价（通常 0 或 1 条）。 */
    private List<EvaluationVO> evaluations;
}
