package com.study.vuePractiseBackend.vo;

import lombok.Data;

import java.util.List;

/** 章节与截图。 */
@Data
public class SubmissionSectionVO {

    private Long id;
    private String title;
    private String content;
    private Integer sortOrder;

    /** 该章节的截图。 */
    private List<TeachingAttachmentVO> attachments;
}
