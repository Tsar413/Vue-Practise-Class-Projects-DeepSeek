package com.study.vuePractiseBackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教师对某个成果版本的评分或退回。
 * 一条评价只对应一个版本；学生再次提交后旧评价保持 isCurrent=0，仍然可查。
 */
@Entity
@Table(name = "teaching_evaluation")
@TableName("teaching_evaluation")
@Data
public class TeachingEvaluation {

    public static final String DECISION_PASS = "PASS";
    public static final String DECISION_REVISE = "REVISE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "submission_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("submission_id")
    private Long submissionId;

    @Column(name = "version_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("version_id")
    private Long versionId;

    @Column(name = "version_no", nullable = false, columnDefinition = "INTEGER")
    @TableField("version_no")
    private Integer versionNo;

    @Column(name = "task_id", nullable = false, columnDefinition = "BIGINT")
    @TableField("task_id")
    private Long taskId;

    @Column(name = "student_id", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @TableField("student_id")
    private String studentId;

    /** 实际评分教师，取自登录身份，不接受客户端提交。 */
    @Column(name = "teacher_id", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @TableField("teacher_id")
    private String teacherId;

    /** PASS 通过 / REVISE 退回修改。 */
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private String decision;

    /** PASS 时必填，范围 0..任务满分。 */
    @Column
    private Integer score;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "is_current", nullable = false, columnDefinition = "TINYINT")
    @TableField("is_current")
    private Integer isCurrent;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "create_time", nullable = false, columnDefinition = "DATETIME")
    @TableField("create_time")
    private LocalDateTime createTime;
}
