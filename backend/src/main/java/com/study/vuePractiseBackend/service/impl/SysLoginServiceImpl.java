package com.study.vuePractiseBackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.study.vuePractiseBackend.dto.SysLoginDTO;
import com.study.vuePractiseBackend.dto.SysLoginReturnDTO;
import com.study.vuePractiseBackend.entity.SysClass;
import com.study.vuePractiseBackend.entity.SysLoginToken;
import com.study.vuePractiseBackend.entity.SysUser;
import com.study.vuePractiseBackend.mapper.SysClassMapper;
import com.study.vuePractiseBackend.mapper.SysLoginMapper;
import com.study.vuePractiseBackend.mapper.SysUserMapper;
import com.study.vuePractiseBackend.service.SysLoginService;
import com.study.vuePractiseBackend.util.PasswordUtil;
import com.study.vuePractiseBackend.util.TokenUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 登录与凭证管理。
 * 网页登录 Token 每次登录轮换并在数据库只保存散列；
 * 学生的长期 API 访问码只在首次生成，重复登录保持不变。
 */
@Service
public class SysLoginServiceImpl extends ServiceImpl<SysLoginMapper, SysLoginToken> implements SysLoginService {

    /** 登录失败原因编码，与控制器分支一一对应。 */
    private static final int EMPTY_PARAM = -2;
    private static final int TOO_LONG = -3;
    private static final int BAD_CREDENTIAL = -4;
    private static final int USER_DISABLED = -5;
    private static final int CLASS_INVALID = -6;
    private static final int ROLE_INVALID = -7;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SysClassMapper sysClassMapper;

    @Override
    @Transactional
    public SysLoginReturnDTO login(SysLoginDTO sysLoginDTO) {
        // 1. 参数检查：账号去首尾空格，密码保持原样
        if (sysLoginDTO == null || sysLoginDTO.getId() == null || sysLoginDTO.getPassword() == null) {
            return failure(EMPTY_PARAM);
        }
        String id = sysLoginDTO.getId().trim();
        String password = sysLoginDTO.getPassword();
        if (id.isBlank() || password.isBlank()) {
            return failure(EMPTY_PARAM);
        }
        if (id.length() > 50 || password.length() > 50) {
            return failure(TOO_LONG);
        }

        // 2. 账号不存在与密码错误统一处理，避免探测账号
        SysUser user = sysUserMapper.selectById(id);
        if (user == null || !matchesPassword(password, user)) {
            return failure(BAD_CREDENTIAL);
        }

        // 3. 账号状态
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            return failure(USER_DISABLED);
        }

        // 4. 学生必须属于启用状态的班级，教师不检查班级
        if ("STUDENT".equals(user.getRole())) {
            String classId = user.getClassId();
            if (classId == null || classId.isBlank()) {
                return failure(CLASS_INVALID);
            }
            SysClass sysClass = sysClassMapper.selectById(classId);
            if (sysClass == null || !Integer.valueOf(1).equals(sysClass.getStatus())) {
                return failure(CLASS_INVALID);
            }
        } else if (!"TEACHER".equals(user.getRole())) {
            return failure(ROLE_INVALID);
        }

        // 5. 串行化同一账号的登录，然后写入或轮换凭证
        //
        //    加锁顺序统一为「sys_user → sys_login_token」，与删除账号路径一致；
        //    顺序不一致会带来交叉等待的风险。
        //    加锁后重新读取账号状态，避免在等待锁期间账号被停用却仍发出凭证。
        SysUser lockedUser = baseMapper.selectUserForUpdate(id);
        if (lockedUser == null) {
            return failure(BAD_CREDENTIAL);
        }
        if (!Integer.valueOf(1).equals(lockedUser.getStatus())) {
            return failure(USER_DISABLED);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireTime = now.plusDays(1);
        String rawToken = TokenUtil.generateToken();
        String tokenHash = TokenUtil.hashToken(rawToken);

        // 在锁内「加锁读」现有记录：
        // 1) sys_user 行的排他锁让同一账号的登录串行化；
        // 2) 加锁读保证能读到其他并发请求刚刚提交的 api_access_code，
        //    不会因为快照读看到旧值而重复生成访问码。
        SysLoginToken existing = baseMapper.selectByUserIdForUpdate(id);

        // 学生首次登录时生成长期访问码；已有访问码则传 null 保持不变
        String apiAccessCode = null;
        if ("STUDENT".equals(lockedUser.getRole())
                && (existing == null
                    || existing.getApiAccessCode() == null
                    || existing.getApiAccessCode().isBlank())) {
            apiAccessCode = TokenUtil.generateToken();
        }

        if (baseMapper.upsertToken(id, tokenHash, apiAccessCode, now, expireTime) < 0) {
            throw new IllegalStateException("写入登录Token失败");
        }

        // 6. 回读最终生效的长期访问码（以数据库为准，保证返回值与实际存储一致）
        SysLoginToken saved = baseMapper.selectByUserId(id);
        if (saved == null) {
            throw new IllegalStateException("登录Token写入后未查询到记录");
        }
        String effectiveApiAccessCode = saved.getApiAccessCode();

        // 7. 更新最后登录时间
        UpdateWrapper<SysUser> userUpdate = new UpdateWrapper<>();
        userUpdate.eq("id", id);
        userUpdate.set("last_login_time", now);
        if (sysUserMapper.update(null, userUpdate) != 1) {
            throw new IllegalStateException("更新最后登录时间失败");
        }

        // 8. 返回登录结果，不返回过期时间
        SysLoginReturnDTO result = new SysLoginReturnDTO();
        result.setStatus(1);
        result.setUserId(id);
        result.setToken(rawToken);
        result.setRealName(user.getRealName());
        result.setRole(user.getRole());
        if ("STUDENT".equals(user.getRole())) {
            result.setApiAccessCode(effectiveApiAccessCode);
        }
        return result;
    }

    @Override
    @Transactional
    public Integer logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return -2;
        }
        rawToken = rawToken.trim();
        if (!rawToken.matches("[0-9a-f]{64}")) {
            return -2;
        }
        UpdateWrapper<SysLoginToken> wrapper = new UpdateWrapper<>();
        wrapper.eq("token_hash", TokenUtil.hashToken(rawToken));
        wrapper.isNull("revoke_time");
        wrapper.set("revoke_time", LocalDateTime.now());
        baseMapper.update(null, wrapper);
        // 凭证不存在或已退出时同样返回成功，退出操作保持幂等
        return 1;
    }

    @Override
    @Transactional
    public void revokeByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户编号不能为空");
        }
        UpdateWrapper<SysLoginToken> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId.trim());
        wrapper.isNull("revoke_time");
        wrapper.set("revoke_time", LocalDateTime.now());
        // 用户可能从未登录，更新 0 条记录是正常情况
        baseMapper.update(null, wrapper);
    }

    @Override
    @Transactional
    public void deleteByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户编号不能为空");
        }
        QueryWrapper<SysLoginToken> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId.trim());
        baseMapper.delete(wrapper);
    }

    /** 使用账号自身保存的盐校验密码，比较采用定长实现避免时序差异。 */
    private boolean matchesPassword(String password, SysUser user) {
        if (user.getPasswordSalt() == null || user.getPasswordHash() == null) {
            return false;
        }
        if (!"SHA-256".equals(user.getPasswordAlgorithm())) {
            return false;
        }
        String calculatedHash = PasswordUtil.hashPassword(password, user.getPasswordSalt());
        return MessageDigest.isEqual(
                calculatedHash.getBytes(StandardCharsets.UTF_8),
                user.getPasswordHash().getBytes(StandardCharsets.UTF_8));
    }

    private SysLoginReturnDTO failure(int status) {
        SysLoginReturnDTO result = new SysLoginReturnDTO();
        result.setStatus(status);
        return result;
    }
}
