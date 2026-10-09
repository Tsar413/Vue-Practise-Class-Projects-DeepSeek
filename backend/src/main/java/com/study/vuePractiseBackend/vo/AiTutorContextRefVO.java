package com.study.vuePractiseBackend.vo;

import lombok.Data;

/**
 * 本次回答引用的真实来源条目。
 * 只由后端从真实接口文档（或真实任务记录）生成，检索不到就返回空列表，绝不编造。
 */
@Data
public class AiTutorContextRefVO {

    /** 接口文档条目。 */
    public static final String TYPE_API = "API";

    /** 接口文档文件本身。 */
    public static final String TYPE_DOC = "DOC";

    /** 实训任务。 */
    public static final String TYPE_TASK = "TASK";

    /** DOC / API / TASK。 */
    private String type;

    /** API 取 operationId；DOC 取文档路径；TASK 取任务 ID 字符串。 */
    private String id;

    /** 展示用标题：API 取 summary，DOC 取文档名，TASK 取任务标题。 */
    private String title;

    /** API 为真实接口路径；DOC 为仓库中的真实文档路径；TASK 无路径时为空串。 */
    private String path;

    /** 真实 summary / description 的前若干字符。 */
    private String excerpt;
}
