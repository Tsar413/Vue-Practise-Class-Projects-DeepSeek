package com.study.vuePractiseBackend.dto;

import lombok.Data;

import java.util.List;

/**
 * 学生保存草稿或正式提交的入参。
 *
 * 注意：studentId / taskId 不由客户端决定。
 *   * 学号取自登录身份；
 *   * 任务来自路径参数；
 *   * 服务端只信任 versionId 之外的业务内容。
 */
@Data
public class TeachingSubmissionDTO {

    /** 成果项目或仓库链接，只允许 http/https。 */
    private String projectUrl;

    /** 完成说明，正式提交必填。 */
    private String content;

    /** 问题与解决过程。 */
    private String process;

    /** 章节与截图归属。 */
    private List<SubmissionSectionDTO> sections;

    /**
     * 客户端一次性幂等键（正式提交必填，长度 8..64）。
     * 同一键重复请求不会生成第二个版本。
     */
    private String requestKey;
}
