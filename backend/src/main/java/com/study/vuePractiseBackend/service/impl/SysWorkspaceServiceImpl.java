package com.study.vuePractiseBackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.study.vuePractiseBackend.entity.*;
import com.study.vuePractiseBackend.mapper.*;
import com.study.vuePractiseBackend.service.SysWorkspaceService;
import com.study.vuePractiseBackend.util.RepairMockDataUtil;
import com.study.vuePractiseBackend.util.TicketMockDataUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 工作空间与基准数据。
 *
 * 数据隔离：所有项目数据都带 workspace_id，
 * 初始化与重置都以「学生 → 工作空间」为边界，绝不跨空间写入。
 *
 * 并发：初始化、重置、删除以及各业务写操作共用
 * sys_workspace 行锁（selectByStudentIdForUpdate / FOR UPDATE），
 * 保证同一学生的操作串行，避免重置与业务写入互相覆盖。
 */
@Service
public class SysWorkspaceServiceImpl extends ServiceImpl<SysWorkspaceMapper, SysWorkspace> implements SysWorkspaceService {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SysClassMapper sysClassMapper;

    @Resource
    private TickerUserMapper tickerUserMapper;

    @Resource
    private TicketActivityMapper ticketActivityMapper;

    @Resource
    private TicketRecordMapper ticketRecordMapper;

    @Resource
    private RepairUserMapper repairUserMapper;

    @Resource
    private RepairDeviceMapper repairDeviceMapper;

    @Resource
    private RepairEvaluationMapper repairEvaluationMapper;

    @Resource
    private RepairOrderMapper repairOrderMapper;

    @Resource
    private RepairOrderImageMapper repairOrderImageMapper;

    @Resource
    private RepairProcessRecordMapper repairProcessRecordMapper;

    @Resource
    private RepairAttachmentMapper repairAttachmentMapper;

    @Resource
    private RepairFileCleanupService repairFileCleanupService;

    /** 不存在则创建，已存在则同步状态；恢复历史空间时保留原 ID 和项目数据。 */
    @Override
    @Transactional
    public void ensureStudentWorkspace(String studentId, Integer status) {
        studentId = checkStudentId(studentId);
        checkStatus(status);
        SysWorkspace workspace = findByStudentId(studentId);
        if (workspace != null) {
            updateWorkspaceStatus(workspace, status);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        workspace = new SysWorkspace();
        workspace.setStudentId(studentId);
        workspace.setStatus(status);
        workspace.setCreateTime(now);
        workspace.setUpdateTime(now);
        if (baseMapper.insert(workspace) != 1) {
            throw new IllegalStateException("创建工作空间失败");
        }
    }

    /** 同步学生工作空间状态；空间不存在视为异常，由调用方回滚。 */
    @Override
    @Transactional
    public void syncStudentWorkspaceStatus(String studentId, Integer status) {
        studentId = checkStudentId(studentId);
        checkStatus(status);
        SysWorkspace workspace = findByStudentId(studentId);
        if (workspace == null) {
            throw new IllegalStateException("学生工作空间不存在");
        }
        updateWorkspaceStatus(workspace, status);
    }

    /** 学生转为教师时暂停历史空间，不存在则跳过。 */
    @Override
    @Transactional
    public void pauseWorkspaceIfPresent(String studentId) {
        studentId = checkStudentId(studentId);
        SysWorkspace workspace = findByStudentId(studentId);
        if (workspace == null) {
            return;
        }
        updateWorkspaceStatus(workspace, 0);
    }

    /** 删除用户时先清理两个项目的数据，再删除工作空间；空间不存在则跳过。 */
    @Override
    @Transactional
    public void deleteWorkspaceIfPresent(String studentId) {
        studentId = checkStudentId(studentId);
        SysWorkspace workspace = baseMapper.selectByStudentIdForUpdate(studentId);
        if (workspace == null) {
            return;
        }
        Long workspaceId = workspace.getId();
        deleteTicketData(workspaceId);
        deleteRepairData(workspaceId);
        if (baseMapper.deleteById(workspaceId) != 1) {
            throw new IllegalStateException("删除工作空间失败");
        }
    }

    @Override
    public List<SysWorkspace> getWorkspacesClassId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("班级编号不能为空");
        }
        // 第一次查询：只取学生学号
        QueryWrapper<SysUser> userWrapper = new QueryWrapper<>();
        userWrapper.select("id");
        userWrapper.eq("class_id", id.trim());
        userWrapper.eq("role", "STUDENT");
        List<SysUser> users = sysUserMapper.selectList(userWrapper);
        if (users.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> studentIds = users.stream().map(SysUser::getId).toList();

        // 第二次查询：一次性获取这些学生的工作空间
        QueryWrapper<SysWorkspace> workspaceWrapper = new QueryWrapper<>();
        workspaceWrapper.in("student_id", studentIds);
        workspaceWrapper.orderByAsc("student_id");
        return baseMapper.selectList(workspaceWrapper);
    }

    @Override
    @Transactional
    public Integer changeWorkspaceStatus(String studentId, Integer status) {
        if (studentId == null || studentId.isBlank()) {
            return -2;
        }
        studentId = studentId.trim();
        if (studentId.length() > 50) {
            return -3;
        }
        if (status == null || (status != 0 && status != 1)) {
            return -5;
        }
        SysWorkspace workspace = findByStudentId(studentId);
        if (workspace == null) {
            return -4;
        }
        // 启用前检查资格，即使空间已经启用也要检查
        if (status == 1) {
            SysUser user = sysUserMapper.selectById(studentId);
            if (user == null) {
                return -6;
            }
            if (!"STUDENT".equals(user.getRole())) {
                return -7;
            }
            if (!Integer.valueOf(1).equals(user.getStatus())) {
                return -8;
            }
            if (user.getClassId() == null || user.getClassId().isBlank()) {
                return -10;
            }
            SysClass sysClass = sysClassMapper.selectById(user.getClassId());
            if (sysClass == null) {
                return -10;
            }
            if (!Integer.valueOf(1).equals(sysClass.getStatus())) {
                return -11;
            }
        }
        updateWorkspaceStatus(workspace, status);
        return 1;
    }

    /** 按班级初始化抢票数据；已有任意抢票数据的空间直接跳过。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Integer initializeTicketClass(String classId) {
        if (classId == null || classId.isBlank()) {
            return -2;
        }
        classId = classId.trim();
        if (classId.length() > 50) {
            return -3;
        }
        if (sysClassMapper.selectById(classId) == null) {
            return -4;
        }
        QueryWrapper<SysUser> wrapper = new QueryWrapper<SysUser>();
        wrapper.eq("class_id", classId).eq("role", "STUDENT").orderByAsc("id");
        List<SysUser> sysUsers = sysUserMapper.selectList(wrapper);
        if (sysUsers.isEmpty()) {
            return -5;
        }
        LocalDateTime now = LocalDateTime.now();
        for (SysUser sysUser : sysUsers) {
            SysWorkspace workspace = baseMapper.selectByStudentIdForUpdate(sysUser.getId());
            if (workspace == null) {
                throw new IllegalStateException("学生工作空间不存在：" + sysUser.getId());
            }
            Long workspaceId = workspace.getId();
            if (hasTicketData(workspaceId)) {
                continue;
            }
            createTicketData(workspaceId, sysUser, now);
        }
        return 1;
    }

    /** 按班级初始化报修数据；已有任意报修数据的空间直接跳过。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Integer initializeRepairClass(String classId) {
        if (classId == null || classId.isBlank()) {
            return -2;
        }
        classId = classId.trim();
        if (classId.length() > 50) {
            return -3;
        }
        if (sysClassMapper.selectById(classId) == null) {
            return -4;
        }
        QueryWrapper<SysUser> userWrapper = new QueryWrapper<>();
        userWrapper.eq("class_id", classId).eq("role", "STUDENT").orderByAsc("id");
        List<SysUser> sysUsers = sysUserMapper.selectList(userWrapper);
        if (sysUsers.isEmpty()) {
            return -5;
        }
        LocalDateTime now = LocalDateTime.now();
        for (SysUser sysUser : sysUsers) {
            SysWorkspace workspace = baseMapper.selectByStudentIdForUpdate(sysUser.getId());
            if (workspace == null) {
                throw new IllegalStateException("学生工作空间不存在：" + sysUser.getId());
            }
            Long workspaceId = workspace.getId();
            if (hasRepairData(workspaceId)) {
                continue;
            }
            createRepairData(workspaceId, sysUser, now);
        }
        return 1;
    }

    /**
     * 重置指定学生的指定项目。
     * TICKET / REPAIR 只影响该项目，ALL 重置两个项目；
     * 其他学生和其他项目的数据完全不受影响；
     * 工作空间本体、学生账号与长期访问码都不改变。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Integer resetWorkspace(String studentId, String project) {
        if (studentId == null || studentId.isBlank()) {
            return -2;
        }
        studentId = studentId.trim();
        if (studentId.length() > 50) {
            return -3;
        }
        if (project == null || project.isBlank()) {
            return -5;
        }
        project = project.trim().toUpperCase(Locale.ROOT);
        if (!"TICKET".equals(project) && !"REPAIR".equals(project) && !"ALL".equals(project)) {
            return -5;
        }
        // 与初始化、删除使用同一把工作空间行锁
        SysWorkspace workspace = baseMapper.selectByStudentIdForUpdate(studentId);
        if (workspace == null) {
            return -4;
        }
        SysUser sysUser = sysUserMapper.selectById(studentId);
        if (sysUser == null) {
            return -6;
        }
        if (!"STUDENT".equals(sysUser.getRole())) {
            return -7;
        }
        Long workspaceId = workspace.getId();
        LocalDateTime now = LocalDateTime.now();
        if ("TICKET".equals(project) || "ALL".equals(project)) {
            deleteTicketData(workspaceId);
            createTicketData(workspaceId, sysUser, now);
        }
        if ("REPAIR".equals(project) || "ALL".equals(project)) {
            deleteRepairData(workspaceId);
            createRepairData(workspaceId, sysUser, now);
        }
        return 1;
    }

    private boolean hasTicketData(Long workspaceId) {
        return tickerUserMapper.selectCount(new QueryWrapper<TicketUser>()
                .eq("workspace_id", workspaceId)) > 0
                || ticketActivityMapper.selectCount(new QueryWrapper<TicketActivity>()
                .eq("workspace_id", workspaceId)) > 0
                || ticketRecordMapper.selectCount(new QueryWrapper<TicketRecord>()
                .eq("workspace_id", workspaceId)) > 0;
    }

    /** 任意 repair 表中已有该工作空间的数据，就跳过初始化。 */
    private boolean hasRepairData(Long workspaceId) {
        return repairUserMapper.selectCount(new QueryWrapper<RepairUser>()
                .eq("workspace_id", workspaceId)) > 0
                || repairDeviceMapper.selectCount(new QueryWrapper<RepairDevice>()
                .eq("workspace_id", workspaceId)) > 0
                || repairOrderMapper.selectCount(new QueryWrapper<RepairOrder>()
                .eq("workspace_id", workspaceId)) > 0
                || repairProcessRecordMapper.selectCount(new QueryWrapper<RepairProcessRecord>()
                .eq("workspace_id", workspaceId)) > 0
                || repairOrderImageMapper.selectCount(new QueryWrapper<RepairOrderImage>()
                .eq("workspace_id", workspaceId)) > 0
                || repairEvaluationMapper.selectCount(new QueryWrapper<RepairEvaluation>()
                .eq("workspace_id", workspaceId)) > 0;
    }

    private void createTicketData(Long workspaceId, SysUser sysUser, LocalDateTime time) {
        List<TicketUser> ticketUsers = TicketMockDataUtil.createUsers(workspaceId);
        List<TicketActivity> ticketActivities = TicketMockDataUtil.createActivities(workspaceId, time);
        for (TicketUser ticketUser : ticketUsers) {
            if (tickerUserMapper.insert(ticketUser) != 1) {
                throw new IllegalStateException("初始化抢票用户失败：" + sysUser.getId());
            }
        }
        for (TicketActivity ticketActivity : ticketActivities) {
            if (ticketActivityMapper.insert(ticketActivity) != 1) {
                throw new IllegalStateException("初始化抢票活动失败：" + sysUser.getId());
            }
        }
    }

    private void createRepairData(Long workspaceId, SysUser sysUser, LocalDateTime time) {
        // 1. 先保存模拟用户并回填主键
        List<RepairUser> users = RepairMockDataUtil.createUsers(workspaceId, time);
        for (RepairUser user : users) {
            if (repairUserMapper.insert(user) != 1) {
                throw new IllegalStateException("初始化维修用户失败：" + sysUser.getId());
            }
        }
        // 2. 再保存设备并回填主键
        List<RepairDevice> devices = RepairMockDataUtil.createDevices(workspaceId, time);
        for (RepairDevice device : devices) {
            if (repairDeviceMapper.insert(device) != 1) {
                throw new IllegalStateException("初始化维修设备失败：" + sysUser.getId());
            }
        }
        // 3. 使用真实用户、设备 ID 生成工单
        List<RepairOrder> orders = RepairMockDataUtil.createOrders(workspaceId, users, devices, time);
        for (RepairOrder order : orders) {
            if (repairOrderMapper.insert(order) != 1) {
                throw new IllegalStateException("初始化维修工单失败：" + sysUser.getId());
            }
        }
        // 4. 使用真实工单 ID 生成处理记录
        List<RepairProcessRecord> records = RepairMockDataUtil.createProcessRecords(orders);
        for (RepairProcessRecord record : records) {
            if (repairProcessRecordMapper.insert(record) != 1) {
                throw new IllegalStateException("初始化维修处理记录失败：" + sysUser.getId());
            }
        }
    }

    /** 清理抢票数据，保留工作空间本体。 */
    private void deleteTicketData(Long workspaceId) {
        ticketRecordMapper.delete(new QueryWrapper<TicketRecord>().eq("workspace_id", workspaceId));
        ticketActivityMapper.delete(new QueryWrapper<TicketActivity>().eq("workspace_id", workspaceId));
        tickerUserMapper.delete(new QueryWrapper<TicketUser>().eq("workspace_id", workspaceId));
    }

    /** 清理报修数据，保留工作空间本体；图片文件通过任务表异步删除。 */
    private void deleteRepairData(Long workspaceId) {
        Set<String> paths = new LinkedHashSet<>();

        // 删除数据库记录前，先取得文件路径
        repairAttachmentMapper.selectList(new LambdaQueryWrapper<RepairAttachment>()
                        .eq(RepairAttachment::getWorkspaceId, workspaceId))
                .forEach(a -> paths.add(a.getImageUrl()));

        repairOrderImageMapper.selectList(new LambdaQueryWrapper<RepairOrderImage>()
                        .eq(RepairOrderImage::getWorkspaceId, workspaceId))
                .forEach(i -> paths.add(i.getImageUrl()));

        // 任务与当前业务事务一起提交，回滚时不删除原图片
        repairFileCleanupService.enqueue(paths);

        repairOrderImageMapper.delete(new QueryWrapper<RepairOrderImage>().eq("workspace_id", workspaceId));
        repairAttachmentMapper.delete(new LambdaQueryWrapper<RepairAttachment>()
                .eq(RepairAttachment::getWorkspaceId, workspaceId));
        repairEvaluationMapper.delete(new QueryWrapper<RepairEvaluation>().eq("workspace_id", workspaceId));
        repairProcessRecordMapper.delete(new QueryWrapper<RepairProcessRecord>().eq("workspace_id", workspaceId));
        repairOrderMapper.delete(new QueryWrapper<RepairOrder>().eq("workspace_id", workspaceId));
        repairDeviceMapper.delete(new QueryWrapper<RepairDevice>().eq("workspace_id", workspaceId));
        repairUserMapper.delete(new QueryWrapper<RepairUser>().eq("workspace_id", workspaceId));
    }

    /** 根据学号查询唯一工作空间。 */
    private SysWorkspace findByStudentId(String studentId) {
        LambdaQueryWrapper<SysWorkspace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysWorkspace::getStudentId, studentId);
        return baseMapper.selectOne(wrapper);
    }

    /** 状态一致时不重复写入。 */
    private void updateWorkspaceStatus(SysWorkspace workspace, Integer status) {
        if (status.equals(workspace.getStatus())) {
            return;
        }
        SysWorkspace update = new SysWorkspace();
        update.setId(workspace.getId());
        update.setStatus(status);
        update.setUpdateTime(LocalDateTime.now());
        if (baseMapper.updateById(update) != 1) {
            throw new IllegalStateException("修改工作空间状态失败");
        }
    }

    private String checkStudentId(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("学号不能为空");
        }
        String id = studentId.trim();
        if (id.length() > 50) {
            throw new IllegalArgumentException("学号不能超过50个字符");
        }
        return id;
    }

    private void checkStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("工作空间状态只能为0或1");
        }
    }
}
