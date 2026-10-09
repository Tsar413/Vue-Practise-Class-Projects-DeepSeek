package com.study.vuePractiseBackend.dto;

import lombok.Data;

import java.util.List;

/** 成果章节（含该章节的截图 ID 列表）。 */
@Data
public class SubmissionSectionDTO {

    private String title;

    private String content;

    private Integer sortOrder;

    /** 归属该章节的截图 ID，必须是本人本任务已上传的附件。 */
    private List<Long> attachmentIds;
}
