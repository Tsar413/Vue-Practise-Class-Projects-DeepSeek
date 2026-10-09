package com.study.vuePractiseBackend.util;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校区中性别名与旧值兼容。
 *
 * 背景：数据库中已有两个具体校区名称的历史记录，这些记录**不允许修改**（禁止为了展示
 * 效果去重写业务数据）。公开文档、提示文案与新建数据改用中性值「校区A / 校区B」，
 * 因此需要一层最小兼容：
 *
 *   * 校验：中性值与两个旧值都接受，未知值一律拒绝；
 *   * 查询：用中性值筛选时同时匹配对应的旧值，保证旧记录能被查出来；
 *   * 提示：只提及中性值，不再回显具体校区名称。
 *
 * 该兼容层不写库、不改数据，只影响校验与查询条件。
 */
public final class CampusAliasUtil {

    public static final String CAMPUS_A = "校区A";
    public static final String CAMPUS_B = "校区B";

    /** 中性值 -> 历史旧值。 */
    private static final Map<String, String> ALIAS_TO_LEGACY = Map.of(
            CAMPUS_A, "新吴校区",
            CAMPUS_B, "藕塘校区");

    /** 历史旧值 -> 中性值。 */
    private static final Map<String, String> LEGACY_TO_ALIAS = Map.of(
            "新吴校区", CAMPUS_A,
            "藕塘校区", CAMPUS_B);

    /** 全部可用值（中性值 + 旧值），用于校验。 */
    private static final Set<String> ACCEPTED = Set.of(
            CAMPUS_A, CAMPUS_B, "新吴校区", "藕塘校区");

    private CampusAliasUtil() {
    }

    /** 是否是可接受的校区值（中性值或历史旧值）。 */
    public static boolean isAccepted(String campus) {
        return campus != null && ACCEPTED.contains(campus.trim());
    }

    /**
     * 查询条件用：先把任意输入**归一**为中性值，再返回应当匹配的全部库内取值。
     *
     * 为什么需要归一：库里既有历史旧值（旧记录），也会有新生成的中性 mock。
     * 如果旧客户端提交旧值、而这里只匹配旧值，就会漏掉新 mock 数据；
     * 反之新客户端提交中性值时也不能漏掉旧记录。因此两个方向都要覆盖，
     * 且只做查询条件扩展，不修改任何原始记录。
     */
    public static List<String> queryValues(String campus) {
        if (campus == null || campus.isBlank()) {
            return List.of();
        }
        String value = campus.trim();
        // 归一：旧值 -> 中性值；已是中性值则保持不变
        String canonical = LEGACY_TO_ALIAS.getOrDefault(value, value);
        String legacy = ALIAS_TO_LEGACY.get(canonical);
        // 未知值原样返回（校验层已负责拒绝，这里不吞掉输入）
        if (legacy == null) {
            return List.of(value);
        }
        return List.of(canonical, legacy);
    }

    /** 展示用：把库内取值转换为中性值（不修改数据库）。 */
    public static String toAlias(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        String value = stored.trim();
        return LEGACY_TO_ALIAS.getOrDefault(value, value);
    }

    /** 校验失败时的统一提示：只提中性值。 */
    public static String invalidMessage() {
        return "校区只能是" + CAMPUS_A + "或" + CAMPUS_B;
    }
}
