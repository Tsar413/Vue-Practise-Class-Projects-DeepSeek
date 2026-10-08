package com.study.vuePractiseBackend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.study.vuePractiseBackend.entity.SysWorkspace;

import java.util.List;

public interface SysWorkspaceService extends IService<SysWorkspace> {

    /** 学生创建时分配；已有历史空间时保留原 ID 与项目数据，只同步状态。 */
    void ensureStudentWorkspace(String studentId, Integer status);

    /** 账号启停时同步；空间不存在视为异常。 */
    void syncStudentWorkspaceStatus(String studentId, Integer status);

    /** 学生转为教师时暂停历史空间，不存在则跳过。 */
    void pauseWorkspaceIfPresent(String studentId);

    /** 删除账号时清理两个项目的数据，再删除工作空间。 */
    void deleteWorkspaceIfPresent(String studentId);

    List<SysWorkspace> getWorkspacesClassId(String id);

    Integer changeWorkspaceStatus(String studentId, Integer status);

    Integer initializeTicketClass(String classId);

    Integer initializeRepairClass(String classId);

    /** 按项目重置：只影响该学生、该项目，其他学生与另一个项目不受影响。 */
    Integer resetWorkspace(String studentId, String project);
}
