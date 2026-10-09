package com.study.vuePractiseBackend.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 任务详情/列表视图。 */
@Data
public class TeachingTaskVO {

    private Long id;
    private String title;
    private String project;
    private String objective;
    private String requirement;
    private String acceptance;
    /** 参考资料链接（多条）。 */
    private List<String> referenceUrls;

    /** 首条参考链接，便于旧界面直接使用。 */
    private String referenceUrl;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deadline;

    private Integer fullScore;
    private Boolean allowLate;
    private Integer status;

    /** 任务创建者学号/工号。 */
    private String teacherId;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /** 已分配的班级。 */
    private List<String> classIds;

    /** 已过截止时间（由服务端时间计算）。 */
    private Boolean expired;

    /** 当前是否还允许学生提交：未关闭且（未过期或允许逾期）。 */
    private Boolean submitAllowed;

    /** 学生视角：本人成果状态与版本号；教师视角为 null。 */
    private Integer myStatus;
    private Integer myVersionNo;
    private Integer myScore;
    private String myDecision;
}
