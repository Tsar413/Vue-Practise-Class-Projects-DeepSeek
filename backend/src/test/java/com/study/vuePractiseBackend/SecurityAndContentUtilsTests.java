package com.study.vuePractiseBackend;

import com.study.vuePractiseBackend.util.PasswordUtil;
import com.study.vuePractiseBackend.util.RepairContentUtil;
import com.study.vuePractiseBackend.util.TokenUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯逻辑单元测试。
 *
 * 这些工具类的行为直接决定登录是否可用、凭证格式是否正确、
 * 以及故障描述是否会引入脚本内容，因此单独做不依赖数据库的断言。
 * 需要数据库与 HTTP 的完整验证见仓库根目录的 scripts/api-verify.sh。
 */
class SecurityAndContentUtilsTests {

    /* ------------------------- 口令散列 ------------------------- */

    @Test
    void 口令散列规则为盐加冒号加口令的SHA256() throws Exception {
        String salt = "a".repeat(64);
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest((salt + ":" + "123456").getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, PasswordUtil.hashPassword("123456", salt));
    }

    @Test
    void 盐长度固定为32字节十六进制() {
        String salt = PasswordUtil.generateSalt();
        assertEquals(64, salt.length());
        assertTrue(salt.matches("[0-9a-f]{64}"));
    }

    @Test
    void 同一口令配合不同盐会得到不同散列() {
        String salt1 = PasswordUtil.generateSalt();
        String salt2 = PasswordUtil.generateSalt();
        assertNotEquals(
                PasswordUtil.hashPassword("123456", salt1),
                PasswordUtil.hashPassword("123456", salt2));
    }

    @Test
    void 口令或盐为空时抛出异常() {
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hashPassword(null, "salt"));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hashPassword("pwd", null));
    }

    /* ------------------------- 凭证 ------------------------- */

    @Test
    void 凭证为64位小写十六进制且不重复() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = TokenUtil.generateToken();
            assertTrue(token.matches("[0-9a-f]{64}"), token);
            assertTrue(tokens.add(token), "生成的凭证出现重复：" + token);
        }
    }

    @Test
    void 凭证散列稳定且与原文不同() throws Exception {
        String token = TokenUtil.generateToken();
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, TokenUtil.hashToken(token));
        assertNotEquals(token, TokenUtil.hashToken(token));
        // 相同输入必须得到相同散列，否则历史登录记录将无法匹配
        assertEquals(TokenUtil.hashToken(token), TokenUtil.hashToken(token));
    }

    /* --------------------- 故障描述富文本清洗 --------------------- */

    @Test
    void 保留允许的标签并去掉脚本() {
        String cleaned = RepairContentUtil.cleanRequiredHtml(
                "<p>空调不制冷</p><script>alert(1)</script>", "故障描述");

        assertTrue(cleaned.contains("空调不制冷"));
        assertFalse(cleaned.toLowerCase().contains("<script"));
        assertFalse(cleaned.contains("alert(1)"));
    }

    @Test
    void 只有标签没有有效文字时被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> RepairContentUtil.cleanRequiredHtml("<p>   </p>", "故障描述"));
        assertThrows(IllegalArgumentException.class,
                () -> RepairContentUtil.cleanRequiredHtml("<p>&nbsp;</p>", "故障描述"));
    }

    @Test
    void 空内容或超长内容被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> RepairContentUtil.cleanRequiredHtml(null, "故障描述"));
        assertThrows(IllegalArgumentException.class,
                () -> RepairContentUtil.cleanRequiredHtml("x".repeat(20001), "故障描述"));
    }
}
