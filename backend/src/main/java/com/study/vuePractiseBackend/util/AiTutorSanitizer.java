package com.study.vuePractiseBackend.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发送给模型前的文本脱敏与长度截断。
 *
 * 覆盖范围：
 *  1. 本平台凭证与凭据形态：64 位小写十六进制（登录 Token 与长期访问码）、{@code Bearer xxx}、
 *     {@code sk-xxx}、{@code jdbc:mysql://...} 连接串；
 *  2. 键值对中的敏感值：password / passwd / pwd / secret / secret_key / token / apiKey / api_key /
 *     apikey / accessCode / access_code / authorization / bearer，值支持 {@code "..."}、{@code '...'}、
 *     {@code ${ENV_VAR}} 与裸值，兼容 JSON、JS 对象与查询串写法；
 *  3. 个人敏感信息：中国大陆手机号、邮箱地址。
 *
 * 键值对只替换「值」并保留键名（例如 {@code {"password":"[已脱敏]"}}），
 * 避免把字段结构一起抹掉导致模型无法辅导；其余模式整段替换为 {@link #MASK}。
 *
 * 纯函数、无状态：不读配置、不写日志、不保存原文；调用方也不要打印脱敏前的原文。
 */
public final class AiTutorSanitizer {

    /** 统一的替换文本。 */
    public static final String MASK = "[已脱敏]";

    /** 超长时的截断标记。 */
    public static final String TRUNCATED = "…（已截断）";

    /** 64 位小写十六进制串：登录 Token 与长期访问码的形态。 */
    private static final Pattern HEX_64 =
            Pattern.compile("(?<![0-9a-fA-F])[0-9a-f]{64}(?![0-9a-fA-F])");

    /** Authorization: Bearer xxx。 */
    private static final Pattern BEARER =
            Pattern.compile("Bearer\\s+[A-Za-z0-9._~+/=:-]+", Pattern.CASE_INSENSITIVE);

    /** OpenAI 风格密钥 sk-xxxxxxxx。 */
    private static final Pattern SK_KEY =
            Pattern.compile("sk-[A-Za-z0-9]{10,}");

    /** jdbc:mysql://... 连接串（含其中的库名、账号与参数）。 */
    private static final Pattern JDBC_MYSQL =
            Pattern.compile("jdbc:mysql://\\S+", Pattern.CASE_INSENSITIVE);

    /** 邮箱地址。 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");

    /** 中国大陆手机号。 */
    private static final Pattern CN_MOBILE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 敏感键名；access[_-]?code 同时覆盖 accessCode / access_code / ACCESS_CODE。 */
    private static final String SECRET_KEY_NAMES =
            "(?<![\\w-])(?:password|passwd|pwd|secret[_-]?key|secret|token|api[_-]?key|apikey"
                    + "|access[_-]?code|authorization|bearer)\\b";

    /**
     * 值可以是双引号串、单引号串、${ENV_VAR} 或裸值；
     * 不含换行，避免把下一行正文一起吞掉。
     */
    private static final String SECRET_VALUE =
            "(\"[^\"\\n]*\"|'[^'\\n]*'|\\$\\{[^}\\n]*\\}|\\S+)";

    /** 普通键值对：只脱敏值。允许键名后紧跟引号（JSON / JS 对象写法）。 */
    private static final Pattern SECRET_ASSIGNMENT =
            Pattern.compile(SECRET_KEY_NAMES + "[\"']?[ \\t]*[=:][ \\t]*" + SECRET_VALUE,
                    Pattern.CASE_INSENSITIVE);

    /** 请求头类键名：值可能含空格（如 Bearer xxx），整行到行尾一并脱敏。 */
    private static final Pattern AUTH_HEADER =
            Pattern.compile("(?<![\\w-])(?:authorization|bearer)\\b[\"']?[ \\t]*[=:][ \\t]*([^\\n]+)",
                    Pattern.CASE_INSENSITIVE);

    /** 整段替换的模式；顺序遵循「先长后短」，避免凭证被拆成片段后留下残余。 */
    private static final List<Pattern> FULL_MASK_PATTERNS =
            List.of(BEARER, SK_KEY, JDBC_MYSQL, HEX_64, EMAIL, CN_MOBILE);

    /** 只替换值的模式；请求头模式先于普通键值对，避免值里的空格截断。 */
    private static final List<Pattern> VALUE_MASK_PATTERNS =
            List.of(AUTH_HEADER, SECRET_ASSIGNMENT);

    private AiTutorSanitizer() {
    }

    /**
     * 先脱敏再截断；超出 maxChars 时截断并追加 {@link #TRUNCATED}。
     * maxChars 非正数视为不允许任何内容，返回空串（调用方应保证配置为正数）。
     */
    public static String sanitize(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        for (Pattern pattern : FULL_MASK_PATTERNS) {
            masked = pattern.matcher(masked).replaceAll(Matcher.quoteReplacement(MASK));
        }
        for (Pattern pattern : VALUE_MASK_PATTERNS) {
            masked = maskValueOnly(pattern, masked);
        }
        if (maxChars <= 0) {
            return "";
        }
        if (masked.length() <= maxChars) {
            return masked;
        }
        if (maxChars <= TRUNCATED.length()) {
            return masked.substring(0, maxChars);
        }
        return masked.substring(0, maxChars - TRUNCATED.length()) + TRUNCATED;
    }

    /** 是否包含任一敏感模式，便于单元测试与自检。 */
    public static boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (Pattern pattern : FULL_MASK_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        for (Pattern pattern : VALUE_MASK_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** 只把捕获组 1（敏感值）替换为 {@link #MASK}，键名与分隔符原样保留。 */
    private static String maskValueOnly(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        do {
            String whole = matcher.group();
            String value = matcher.group(1);
            String prefix = (value == null || value.isEmpty())
                    ? whole
                    : whole.substring(0, whole.length() - value.length());
            matcher.appendReplacement(result, Matcher.quoteReplacement(prefix + MASK));
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }
}
