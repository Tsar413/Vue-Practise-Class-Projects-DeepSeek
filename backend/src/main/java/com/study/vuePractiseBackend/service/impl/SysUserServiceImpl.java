package com.study.vuePractiseBackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.study.vuePractiseBackend.dto.StudentImportResultDTO;
import com.study.vuePractiseBackend.dto.SysUserDTO;
import com.study.vuePractiseBackend.entity.SysClass;
import com.study.vuePractiseBackend.entity.SysUser;
import com.study.vuePractiseBackend.mapper.SysClassMapper;
import com.study.vuePractiseBackend.mapper.SysUserMapper;
import com.study.vuePractiseBackend.service.SysLoginService;
import com.study.vuePractiseBackend.service.SysUserService;
import com.study.vuePractiseBackend.service.SysWorkspaceService;
import com.study.vuePractiseBackend.util.PasswordUtil;
import com.study.vuePractiseBackend.util.StudentExcelUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统账号管理。
 * 新建账号初始密码固定为 123456，并立即生成随机盐做散列存储；
 * 学生账号创建成功后自动分配工作空间。
 */
@Slf4j
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Resource
    private SysWorkspaceService sysWorkspaceService;

    @Resource
    private SysClassMapper sysClassMapper;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private SysLoginService sysLoginService;

    @Override
    @Transactional
    public Integer createOneSysUser(SysUserDTO sysUserDTO) {
        if (sysUserDTO == null || sysUserDTO.getId() == null
                || sysUserDTO.getRealName() == null || sysUserDTO.getRole() == null) {
            return -2;
        }
        String id = sysUserDTO.getId().trim();
        String username = sysUserDTO.getUsername() == null ? "" : sysUserDTO.getUsername().trim();
        String realName = sysUserDTO.getRealName().trim();
        String role = sysUserDTO.getRole().trim();
        String classId = sysUserDTO.getClassId() == null ? "" : sysUserDTO.getClassId().trim();

        if (id.isBlank() || realName.isBlank() || role.isBlank()
                || (!role.equals("TEACHER") && !role.equals("STUDENT"))) {
            return -3;
        }
        if (id.length() > 50 || username.length() > 50
                || realName.length() > 50 || classId.length() > 50) {
            return -7;
        }

        if (role.equals("STUDENT")) {
            if (classId.isBlank()) {
                return -4;
            }
            SysClass sysClass = sysClassMapper.selectById(classId);
            if (sysClass == null) {
                return -5;
            }
            if (!Integer.valueOf(1).equals(sysClass.getStatus())) {
                return -8;
            }
        }

        if (baseMapper.selectById(id) != null) {
            return -6;
        }

        LocalDateTime now = LocalDateTime.now();
        SysUser sysUser = new SysUser();
        sysUser.setId(id);
        sysUser.setUsername(username.isBlank() ? realName : username);
        sysUser.setRealName(realName);
        sysUser.setRole(role);
        sysUser.setClassId("STUDENT".equals(role) ? classId : null);
        sysUser.setStatus(1);
        sysUser.setCreateTime(now);
        sysUser.setUpdateTime(now);
        sysUser.setLastLoginTime(null);

        String salt = PasswordUtil.generateSalt();
        sysUser.setPasswordSalt(salt);
        sysUser.setPasswordHash(PasswordUtil.hashPassword("123456", salt));
        sysUser.setPasswordAlgorithm("SHA-256");

        if (baseMapper.insert(sysUser) != 1) {
            throw new IllegalStateException("创建用户失败");
        }

        if ("STUDENT".equals(role)) {
            sysWorkspaceService.ensureStudentWorkspace(id, 1);
        }
        return 1;
    }

    /**
     * 批量导入。
     * 每一行使用独立事务：单行失败只回滚该行，其他行继续处理；
     * 学号已存在的行直接跳过，不修改已有账号。
     */
    @Override
    public StudentImportResultDTO importStudents(MultipartFile file) {
        List<StudentExcelUtil.StudentRow> rows = StudentExcelUtil.readStudents(file);
        StudentImportResultDTO report = new StudentImportResultDTO();
        report.setTotal(rows.size());

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (StudentExcelUtil.StudentRow row : rows) {

            // 文件中的单行格式错误
            if (row.errorMessage() != null) {
                report.setFailedCount(report.getFailedCount() + 1);
                report.getDetails().add(new StudentImportResultDTO.RowResult(
                        row.rowNumber(), row.id(), "FAILED", row.errorMessage()));
                continue;
            }

            SysUserDTO dto = new SysUserDTO();
            dto.setId(row.id());
            dto.setRealName(row.realName());
            dto.setUsername(row.realName());
            dto.setClassId(row.classId());
            dto.setRole("STUDENT");

            try {
                Integer result = template.execute(transactionStatus -> {
                    // 优先检查编号：已存在则跳过，不修改已有账号
                    if (baseMapper.selectById(row.id()) != null) {
                        return -6;
                    }
                    Integer createResult = createOneSysUser(dto);
                    if (createResult == null || createResult != 1) {
                        transactionStatus.setRollbackOnly();
                    }
                    return createResult;
                });

                // execute 返回时该行事务已经处理完成
                if (Integer.valueOf(1).equals(result)) {
                    report.setSuccessCount(report.getSuccessCount() + 1);
                } else if (Integer.valueOf(-6).equals(result)) {
                    report.setSkippedCount(report.getSkippedCount() + 1);
                    report.getDetails().add(new StudentImportResultDTO.RowResult(
                            row.rowNumber(), row.id(), "SKIPPED", "用户编号已存在，未修改已有账号"));
                } else {
                    report.setFailedCount(report.getFailedCount() + 1);
                    report.getDetails().add(new StudentImportResultDTO.RowResult(
                            row.rowNumber(), row.id(), "FAILED", getImportFailureMessage(result)));
                }
            } catch (RuntimeException e) {
                // 在单行事务外捕获：该行已回滚，后续行继续处理
                log.error("学生导入失败，Excel行号：{}，学号：{}", row.rowNumber(), row.id(), e);
                report.setFailedCount(report.getFailedCount() + 1);
                report.getDetails().add(new StudentImportResultDTO.RowResult(
                        row.rowNumber(), row.id(), "FAILED",
                        "创建失败，该行事务未成功完成，请检查后端日志"));
            }
        }
        return report;
    }

    private String getImportFailureMessage(Integer result) {
        if (result == null) {
            return "创建失败";
        }
        return switch (result) {
            case -2 -> "必填字段缺失";
            case -3 -> "用户信息不合法";
            case -4 -> "班级编号不能为空";
            case -5 -> "班级不存在";
            case -7 -> "字段长度超过限制";
            case -8 -> "班级已停用";
            default -> "创建失败";
        };
    }

    @Override
    @Transactional
    public Integer changeUser(SysUserDTO sysUserDTO) {
        if (sysUserDTO == null || sysUserDTO.getId() == null
                || sysUserDTO.getRealName() == null || sysUserDTO.getRole() == null) {
            return -2;
        }
        String id = sysUserDTO.getId().trim();
        String username = sysUserDTO.getUsername() == null ? "" : sysUserDTO.getUsername().trim();
        String realName = sysUserDTO.getRealName().trim();
        String role = sysUserDTO.getRole().trim();
        String classId = sysUserDTO.getClassId() == null ? "" : sysUserDTO.getClassId().trim();

        if (id.isBlank() || realName.isBlank() || role.isBlank()
                || (!role.equals("TEACHER") && !role.equals("STUDENT"))) {
            return -3;
        }
        if (id.length() > 50 || username.length() > 50
                || realName.length() > 50 || classId.length() > 50) {
            return -7;
        }
        if ("STUDENT".equals(role)) {
            if (classId.isBlank()) {
                return -5;
            }
            SysClass sysClass = sysClassMapper.selectById(classId);
            if (sysClass == null) {
                return -5;
            }
            if (!Integer.valueOf(1).equals(sysClass.getStatus())) {
                return -8;
            }
        }

        SysUser sysUser = baseMapper.selectById(id);
        if (sysUser == null) {
            return -4;
        }

        String changeRole = sysUser.getRole();
        UpdateWrapper<SysUser> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id);
        wrapper.set("role", role);
        wrapper.set("username", username.isBlank() ? realName : username);
        wrapper.set("real_name", realName);
        wrapper.set("class_id", "TEACHER".equals(role) ? null : classId);
        wrapper.set("update_time", LocalDateTime.now());
        if (baseMapper.update(null, wrapper) != 1) {
            throw new IllegalStateException("修改用户失败");
        }

        // 角色变化时同步工作空间：学生启用 / 恢复，教师暂停
        if (!role.equals(changeRole)) {
            if ("STUDENT".equals(role)) {
                sysWorkspaceService.ensureStudentWorkspace(id, sysUser.getStatus());
            } else {
                sysWorkspaceService.pauseWorkspaceIfPresent(id);
            }
        }
        return 1;
    }

    @Override
    @Transactional
    public Integer changeUserStatus(String id, Integer status) {
        if (id == null) {
            return -2;
        }
        id = id.trim();
        if (id.isBlank()) {
            return -2;
        }
        if (id.length() > 50) {
            return -3;
        }
        if (status == null || (status != 0 && status != 1)) {
            return -5;
        }
        SysUser sysUser = baseMapper.selectById(id);
        if (sysUser == null) {
            return -4;
        }
        sysUser.setStatus(status);
        sysUser.setUpdateTime(LocalDateTime.now());
        if (baseMapper.updateById(sysUser) != 1) {
            throw new IllegalStateException("修改用户状态失败");
        }
        if ("STUDENT".equals(sysUser.getRole())) {
            sysWorkspaceService.syncStudentWorkspaceStatus(id, status);
        }
        if (status == 0) {
            // 停用账号时撤销网页登录；长期访问码保留，随账号停用一并被拒绝
            sysLoginService.revokeByUserId(id);
        }
        return 1;
    }

    @Override
    @Transactional
    public Integer deleteById(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("用户编号不能为空");
        }
        id = id.trim();
        // 加锁顺序统一为「sys_user → sys_login_token」，与登录路径一致，
        // 避免两条路径因加锁顺序不同而互相等待。
        baseMapper.selectByIdForUpdate(id);
        // 删除网页登录凭证和长期 API 访问码
        sysLoginService.deleteByUserId(id);
        // 清理两个项目的数据与工作空间
        sysWorkspaceService.deleteWorkspaceIfPresent(id);
        // 最后删除用户；不存在或删除失败时前面的操作一起回滚
        if (baseMapper.deleteById(id) != 1) {
            throw new IllegalStateException("删除用户失败");
        }
        return 1;
    }
}
