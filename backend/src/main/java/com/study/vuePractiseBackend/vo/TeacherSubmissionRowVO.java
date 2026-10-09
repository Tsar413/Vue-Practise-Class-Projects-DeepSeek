package com.study.vuePractiseBackend.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/** 教师按班级查看学生提交情况的一行。 */
@Data
public class TeacherSubmissionRowVO {

    private String studentId;
    private String studentName;
    private String classId;

    private Long submissionId;

    /** NOT_SUBMITTED / DRAFT / SUBMITTED / EVALUATED / REVISING。 */
    private String state;

    private Integer versionNo;
    private Boolean late;

    /** 最新版本的评价摘要。 */
    private Integer latestScore;
    private String latestDecision;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submitTime;
}
