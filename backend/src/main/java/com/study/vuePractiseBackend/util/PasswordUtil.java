package com.study.vuePractiseBackend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 口令处理。
 * 散列规则与参考项目一致：SHA-256(salt + ":" + password)，十六进制小写。
 * 保持同一规则才能校验参考项目已有的账号数据。
 */
public final class PasswordUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PasswordUtil() {
    }

    /** 生成 32 字节随机盐，返回 64 个字符的十六进制字符串。 */
    public static String generateSalt() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 计算口令散列。 */
    public static String hashPassword(String password, String salt) {
        if (password == null || salt == null) {
            throw new IllegalArgumentException("密码和盐不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前环境不支持SHA-256", e);
        }
    }
}
