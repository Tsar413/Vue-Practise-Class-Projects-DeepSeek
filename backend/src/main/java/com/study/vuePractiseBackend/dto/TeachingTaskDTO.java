package com.study.vuePractiseBackend.dto;

import lombok.Data;

import java.util.List;

/** 教师新建/编辑实训任务的入参。 */
@Data
public class TeachingTaskDTO {

    /** 新建时为空，编辑时必填。 */
    private Long id;

    private String title;

    /** TICKET 或 REPAIR。 */
    private String project;

    private String objective;

    private String requirement;

    private String acceptance;

    /**
     * 参考资料链接（可多条），每条都必须是 http/https。
     * 兼容旧客户端的单值字段 referenceUrl：两者同时存在时以列表为准并与单值合并去重。
     */
    private List<String> referenceUrls;

    /** 单条参考链接（兼容保留）。 */
    private String referenceUrl;

    /** 格式 yyyy-MM-dd HH:mm:ss。 */
    private String deadline;

    private Integer fullScore;

    private Boolean allowLate;

    /** 分配给哪些班级。 */
    private List<String> classIds;
}
