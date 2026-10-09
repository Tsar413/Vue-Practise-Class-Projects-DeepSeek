package com.study.vuePractiseBackend.vo;

import lombok.Data;

/** 任务维度的班级统计。 */
@Data
public class TeacherTaskStatsVO {

    private Long taskId;
    private String taskTitle;
    private Integer status;
    private Integer fullScore;

    /** 应提交人数（已分配班级的在读学生）。 */
    private Integer expectedCount;
    /** 未提交人数。 */
    private Integer notSubmittedCount;
    /** 待评价人数（已正式提交、尚无当前评价）。 */
    private Integer pendingCount;
    /** 已评价人数（当前评价为通过）。 */
    private Integer evaluatedCount;
    /** 需修改人数（被退回等待重交）。 */
    private Integer revisingCount;
}
