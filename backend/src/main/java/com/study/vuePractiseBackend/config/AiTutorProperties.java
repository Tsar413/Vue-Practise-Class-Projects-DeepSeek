package com.study.vuePractiseBackend.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 辅导配置。
 *
 * 密钥只从环境变量读取（AI_TUTOR_API_KEY），不写入仓库、不打印、不回传前端。
 * 本类不做任何「读取文件中的密钥」动作，也不读取其他应用的凭据。
 *
 * 安全：{@code apiKey} 标注 {@link ToString.Exclude}，
 * 避免 Lombok 生成的 toString 把密钥写进日志或异常信息。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.tutor")
public class AiTutorProperties {

    /** OpenAI 兼容端点的基础地址，例如 https://api.deepseek.com。 */
    private String baseUrl = "";

    /** 模型名。 */
    private String model = "";

    /** 密钥；为空表示未配置，接口返回“AI辅导暂未配置”而不调用模型。 */
    @ToString.Exclude
    private String apiKey = "";

    /** 单次请求的连接超时（秒）。 */
    private int connectTimeoutSeconds = 5;

    /** 单次请求的整体读取超时（秒），同时作为总时限。 */
    private int timeoutSeconds = 30;

    /** 上游响应体允许的最大字节数，超过即中断读取并报错，避免无限缓冲。 */
    private int maxResponseBytes = 262144;

    /** 请求模型生成的最大 token 数（在请求体里实际下发，不只是截断保存）。 */
    private int maxTokens = 1200;

    /** 结构化文档上下文的最大字符数。 */
    private int maxContextChars = 12000;

    /** 单次提问的最大字符数。 */
    private int maxQuestionChars = 2000;

    /** 代码片段最大字符数。 */
    private int maxCodeChars = 4000;

    /** 报错文本最大字符数。 */
    private int maxErrorChars = 2000;

    /** 携带的历史消息条数上限。 */
    private int maxHistoryMessages = 8;

    /** 模型输出保存与返回的最大字符数。 */
    private int maxOutputChars = 4000;

    /** 每名学生的会话数上限。 */
    private int maxConversationsPerStudent = 50;

    /** 两次提问之间的最小间隔秒数。 */
    private int minIntervalSeconds = 5;

    /** 每名学生每日提问上限。 */
    private int dailyLimitPerStudent = 100;

    /** 每名学生同时进行的提问数上限。 */
    private int maxConcurrentPerStudent = 1;

    /** 全局同时进行的上游请求数上限，避免排队请求占满线程。 */
    private int maxConcurrentGlobal = 4;

    /** 全局并发已满时的等待上限（毫秒）；超时直接返回“稍后再试”，不无限排队。 */
    private int acquireTimeoutMillis = 2000;

    /** 是否开启模型思考模式；默认关闭以控制延迟。 */
    private boolean thinkingEnabled = false;

    /** 未配置时给前端的统一提示。 */
    public String notConfiguredNotice() {
        return "AI辅导暂未配置";
    }

    /** 三项都齐备才算已配置。 */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && model != null && !model.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }
}
