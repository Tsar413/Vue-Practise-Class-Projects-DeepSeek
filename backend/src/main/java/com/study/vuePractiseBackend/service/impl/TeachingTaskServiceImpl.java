package com.study.vuePractiseBackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.study.vuePractiseBackend.dto.TeachingTaskDTO;
import com.study.vuePractiseBackend.entity.SysClass;
import com.study.vuePractiseBackend.entity.SysUser;
import com.study.vuePractiseBackend.entity.TeachingEvaluation;
import com.study.vuePractiseBackend.entity.TeachingSubmission;
import com.study.vuePractiseBackend.entity.TeachingSubmissionVersion;
import com.study.vuePractiseBackend.entity.TeachingTask;
import com.study.vuePractiseBackend.entity.TeachingTaskClass;
import com.study.vuePractiseBackend.exception.BusinessExceptions;
import com.study.vuePractiseBackend.mapper.SysClassMapper;
import com.study.vuePractiseBackend.mapper.SysUserMapper;
import com.study.vuePractiseBackend.mapper.TeachingEvaluationMapper;
import com.study.vuePractiseBackend.mapper.TeachingSubmissionMapper;
import com.study.vuePractiseBackend.mapper.TeachingSubmissionVersionMapper;
import com.study.vuePractiseBackend.mapper.TeachingTaskClassMapper;
import com.study.vuePractiseBackend.mapper.TeachingTaskMapper;
import com.study.vuePractiseBackend.service.TeachingTaskService;
import com.study.vuePractiseBackend.vo.TeacherSubmissionRowVO;
import com.study.vuePractiseBackend.vo.TeacherTaskStatsVO;
import com.study.vuePractiseBackend.vo.TeachingTaskVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeParseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实训任务：教师创建/编辑/发布/关闭，学生只读查看本人班级已发布任务。
 *
 * 权限与数据边界：
 *   * 教师身份与学号一律来自登录 Token（请求属性），不接受客户端提交；
 *   * 学生只能看到「已发布或已关闭」且分配给本人班级的任务；
 *   * 任务编辑不会让已存在的提交无法访问，也不会让已有分数越界。
 */
@Service
public class TeachingTaskServiceImpl implements TeachingTaskService {

    /**
     * 严格时间格式：yyyy-MM-dd HH:mm:ss。
     *
     * 必须使用 STRICT 解析并固定四位年等字段宽度：
     * 默认的 SMART 解析会把 2030-02-30 这类不存在的日期「智能纠正」为 2 月末，
     * 从而接受非法输入；STRICT 会直接抛出异常，转成 400。
     */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final int MAX_TITLE = 200;
    private static final int MAX_TEXT = 20000;
    private static final int MAX_URL = 500;
    private static final int MAX_FULL_SCORE = 1000;
    private static final int MAX_REFERENCE_URLS = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private TeachingTaskMapper taskMapper;

    @Resource
    private TeachingTaskClassMapper taskClassMapper;

    @Resource
    private TeachingSubmissionMapper submissionMapper;

    @Resource
    private TeachingSubmissionVersionMapper versionMapper;

    @Resource
    private TeachingEvaluationMapper evaluationMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SysClassMapper sysClassMapper;

    /** 校验教师角色；返回教师工号。 */
    public String requireTeacher(String actorId, String actorRole) {
        if (actorId == null || actorId.isBlank()) {
            throw BusinessExceptions.forbidden("请先登录");
        }
        if (!"TEACHER".equals(actorRole)) {
            throw BusinessExceptions.forbidden("仅教师可以执行此操作");
        }
        SysUser teacher = sysUserMapper.selectById(actorId);
        if (teacher == null || !Integer.valueOf(1).equals(teacher.getStatus())) {
            throw BusinessExceptions.forbidden("教师账号不存在或已停用");
        }
        return actorId;
    }

    /** 读取学生账号；学号由登录身份确定。 */
    public SysUser requireStudent(String actorId, String actorRole) {
        if (actorId == null || actorId.isBlank()) {
            throw BusinessExceptions.forbidden("请先登录");
        }
        if (!"STUDENT".equals(actorRole)) {
            throw BusinessExceptions.forbidden("仅学生可以执行此操作");
        }
        SysUser student = sysUserMapper.selectById(actorId);
        if (student == null || !Integer.valueOf(1).equals(student.getStatus())) {
            throw BusinessExceptions.forbidden("学生账号不存在或已停用");
        }
        if (student.getClassId() == null || student.getClassId().isBlank()) {
            throw BusinessExceptions.forbidden("账号未关联有效班级");
        }
        return student;
    }

    @Override
    public List<TeachingTaskVO> listForTeacher(String teacherId) {
        List<TeachingTask> tasks = taskMapper.selectList(
                new QueryWrapper<TeachingTask>().orderByDesc("id"));
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> classMap = classIdsOf(tasks.stream().map(TeachingTask::getId).toList());
        return tasks.stream()
                .map(t -> toVO(t, classMap.getOrDefault(t.getId(), List.of()), null))
                .toList();
    }

    @Override
    public List<TeachingTaskVO> listForStudent(SysUser student) {
        // 先找出分配给本人班级的任务，再过滤掉草稿
        List<TeachingTaskClass> assigned = taskClassMapper.selectList(
                new QueryWrapper<TeachingTaskClass>().eq("class_id", student.getClassId()));
        if (assigned.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = assigned.stream().map(TeachingTaskClass::getTaskId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<TeachingTask> tasks = taskMapper.selectList(
                new QueryWrapper<TeachingTask>().in("id", ids).ne("status", TeachingTask.STATUS_DRAFT)
                        .orderByDesc("id"));
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> classMap = classIdsOf(tasks.stream().map(TeachingTask::getId).toList());
        Map<Long, TeachingSubmission> mine = mySubmissions(
                tasks.stream().map(TeachingTask::getId).toList(), student.getId());
        return tasks.stream()
                .map(t -> toVO(t, classMap.getOrDefault(t.getId(), List.of()), mine.get(t.getId())))
                .toList();
    }

    @Override
    public TeachingTaskVO detailForTeacher(Long taskId) {
        TeachingTask task = requireTask(taskId);
        return toVO(task, classIdsOf(List.of(task.getId())).getOrDefault(task.getId(), List.of()), null);
    }

    @Override
    public TeachingTaskVO detailForStudent(SysUser student, Long taskId) {
        TeachingTask task = requireTask(taskId);
        if (Integer.valueOf(TeachingTask.STATUS_DRAFT).equals(task.getStatus())) {
            // 草稿对学生完全不可见，即使猜到 ID 也返回 404
            throw BusinessExceptions.notFound("任务不存在");
        }
        if (!isAssignedToClass(taskId, student.getClassId())) {
            throw BusinessExceptions.notFound("任务不存在");
        }
        TeachingSubmission mine = submissionMapper.selectOne(
                new QueryWrapper<TeachingSubmission>()
                        .eq("task_id", taskId).eq("student_id", student.getId()));
        return toVO(task, classIdsOf(List.of(taskId)).getOrDefault(taskId, List.of()), mine);
    }

    /** 学生访问任务时的统一校验：存在、已发布（或已关闭）、分配给本人班级。 */
    @Override
    public TeachingTask requireVisibleToStudent(Long taskId, String classId) {
        TeachingTask task = requireTask(taskId);
        if (Integer.valueOf(TeachingTask.STATUS_DRAFT).equals(task.getStatus())) {
            throw BusinessExceptions.notFound("任务不存在");
        }
        if (!isAssignedToClass(taskId, classId)) {
            throw BusinessExceptions.notFound("任务不存在");
        }
        return task;
    }

    /** 教师访问任务时的统一校验。 */
    @Override
    public TeachingTask requireTaskForTeacher(Long taskId) {
        return requireTask(taskId);
    }

    @Override
    @Transactional
    public TeachingTaskVO createTask(String teacherId, TeachingTaskDTO dto) {
        String title = requireText(dto.getTitle(), "任务标题", MAX_TITLE);
        String project = requireProject(dto.getProject());
        String requirement = requireText(dto.getRequirement(), "任务要求", MAX_TEXT);
        String objective = optionalText(dto.getObjective(), "教学目标", MAX_TEXT);
        String acceptance = optionalText(dto.getAcceptance(), "验收标准", MAX_TEXT);
        String referenceUrls = encodeReferenceUrls(dto);
        LocalDateTime deadline = parseTime(dto.getDeadline(), "截止时间");
        int fullScore = requireScore(dto.getFullScore());
        int allowLate = Boolean.TRUE.equals(dto.getAllowLate()) ? 1 : 0;
        List<String> classIds = requireClassIds(dto.getClassIds());

        LocalDateTime now = LocalDateTime.now();
        TeachingTask task = new TeachingTask();
        task.setTitle(title);
        task.setProject(project);
        task.setObjective(objective);
        task.setRequirement(requirement);
        task.setAcceptance(acceptance);
        task.setReferenceUrl(encodeReferenceUrls(dto));
        task.setDeadline(deadline);
        task.setFullScore(fullScore);
        task.setAllowLate(allowLate);
        // 新建一律为草稿，避免误发布
        task.setStatus(TeachingTask.STATUS_DRAFT);
        task.setTeacherId(teacherId);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        if (taskMapper.insert(task) != 1) {
            throw new IllegalStateException("创建实训任务失败");
        }
        replaceClasses(task.getId(), classIds);
        return toVO(task, classIds, null);
    }

    @Override
    @Transactional
    public TeachingTaskVO updateTask(String teacherId, TeachingTaskDTO dto) {
        if (dto.getId() == null) {
            throw BusinessExceptions.badRequest("缺少任务编号");
        }
        // 与发布/关闭/学生提交/教师评价使用同一把任务行锁：
        // 加锁后重新「当前读」任务、提交与评价，避免并发下绕过下面的保护判断。
        // 锁顺序统一为：teaching_task → teaching_submission → 版本/评价。
        TeachingTask task = taskMapper.selectForUpdate(dto.getId());
        if (task == null) {
            throw BusinessExceptions.notFound("任务不存在");
        }

        String project = requireProject(dto.getProject());
        int fullScore = requireScore(dto.getFullScore());
        LocalDateTime deadline = parseTime(dto.getDeadline(), "截止时间");
        List<String> classIds = requireClassIds(dto.getClassIds());

        // 已有关联提交时，不允许改动会破坏历史数据的字段
        List<TeachingSubmission> submissions = submissionsOf(task.getId());
        if (!submissions.isEmpty()) {
            if (!task.getProject().equals(project)) {
                throw BusinessExceptions.conflict(
                        "该任务已有学生成果，不能再更换所属项目；如需更换请新建任务");
            }
            Integer maxScore = evaluationMapper.selectList(
                            new QueryWrapper<TeachingEvaluation>().eq("task_id", task.getId()))
                    .stream().map(TeachingEvaluation::getScore)
                    .filter(java.util.Objects::nonNull)
                    .max(Integer::compareTo).orElse(null);
            if (maxScore != null && fullScore < maxScore) {
                throw BusinessExceptions.conflict(
                        "满分不能低于已给出的评分（当前最高分 " + maxScore + "）");
            }
        }

        // 再次核对班级分配：不允许移除已有成果的班级
        List<String> currentClasses = classIdsOf(List.of(task.getId()))
                .getOrDefault(task.getId(), List.of());
        Set<String> submittedClasses = submissions.stream()
                .map(TeachingSubmission::getClassId)
                .collect(Collectors.toCollection(HashSet::new));
        for (String removed : currentClasses) {
            if (!classIds.contains(removed) && submittedClasses.contains(removed)) {
                throw BusinessExceptions.conflict(
                        "班级 " + removed + " 已有学生提交成果，不能取消该任务的班级分配");
            }
        }

        task.setTitle(requireText(dto.getTitle(), "任务标题", MAX_TITLE));
        task.setProject(project);
        task.setObjective(optionalText(dto.getObjective(), "教学目标", MAX_TEXT));
        task.setRequirement(requireText(dto.getRequirement(), "任务要求", MAX_TEXT));
        task.setAcceptance(optionalText(dto.getAcceptance(), "验收标准", MAX_TEXT));
        task.setReferenceUrl(encodeReferenceUrls(dto));
        task.setDeadline(deadline);
        task.setFullScore(fullScore);
        task.setAllowLate(Boolean.TRUE.equals(dto.getAllowLate()) ? 1 : 0);
        task.setUpdateTime(LocalDateTime.now());
        if (taskMapper.updateById(task) != 1) {
            throw new IllegalStateException("更新实训任务失败");
        }
        replaceClasses(task.getId(), classIds);
        return toVO(task, classIds, null);
    }

    @Override
    @Transactional
    public TeachingTaskVO changeStatus(Long taskId, String teacherId, String action) {
        if (action == null || action.isBlank()) {
            throw BusinessExceptions.badRequest("缺少操作类型");
        }
        String normalized = action.trim().toUpperCase(java.util.Locale.ROOT);
        // 加锁读取，避免与「学生提交」并发时出现「刚提交就关闭」的竞态判断
        TeachingTask task = taskMapper.selectForUpdate(taskId);
        if (task == null) {
            throw BusinessExceptions.notFound("任务不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        switch (normalized) {
            case "PUBLISH" -> {
                if (Integer.valueOf(TeachingTask.STATUS_PUBLISHED).equals(task.getStatus())) {
                    throw BusinessExceptions.conflict("任务已经是发布状态");
                }
                if (Integer.valueOf(TeachingTask.STATUS_CLOSED).equals(task.getStatus())) {
                    throw BusinessExceptions.conflict("已关闭的任务不能重新发布，请新建任务");
                }
                List<String> classIds = classIdsOf(List.of(taskId))
                        .getOrDefault(taskId, List.of());
                if (classIds.isEmpty()) {
                    throw BusinessExceptions.badRequest("发布前必须至少分配一个班级");
                }
                task.setStatus(TeachingTask.STATUS_PUBLISHED);
            }
            case "CLOSE" -> {
                if (Integer.valueOf(TeachingTask.STATUS_DRAFT).equals(task.getStatus())) {
                    throw BusinessExceptions.conflict("草稿任务无需关闭，可直接删除或保留");
                }
                // 关闭只改变状态：已有提交、版本、截图与评价全部保留
                task.setStatus(TeachingTask.STATUS_CLOSED);
            }
            default -> throw BusinessExceptions.badRequest("操作类型只能是 PUBLISH 或 CLOSE");
        }
        task.setUpdateTime(now);
        if (taskMapper.updateById(task) != 1) {
            throw new IllegalStateException("更新任务状态失败");
        }
        return toVO(task, classIdsOf(List.of(taskId)).getOrDefault(taskId, List.of()), null);
    }

    @Override
    public List<TeacherSubmissionRowVO> listSubmissions(Long taskId, String classId) {
        TeachingTask task = requireTask(taskId);
        List<String> assignedClasses = classIdsOf(List.of(task.getId()))
                .getOrDefault(task.getId(), List.of());
        List<String> scope = classId == null || classId.isBlank()
                ? assignedClasses
                : assignedClasses.stream().filter(classId::equals).toList();
        if (scope.isEmpty()) {
            return List.of();
        }

        // 应提交学生：已分配班级中的在读学生
        List<SysUser> students = sysUserMapper.selectList(new QueryWrapper<SysUser>()
                .in("class_id", scope).eq("role", "STUDENT").eq("status", 1)
                .orderByAsc("class_id").orderByAsc("id"));
        List<TeachingSubmission> submissions = submissionsOf(task.getId());
        Map<String, TeachingSubmission> byStudent = submissions.stream()
                .collect(Collectors.toMap(TeachingSubmission::getStudentId, Function.identity(), (a, b) -> a));

        List<TeachingEvaluation> evaluations = evaluationMapper.selectList(
                new QueryWrapper<TeachingEvaluation>().eq("task_id", task.getId()));
        Map<Long, TeachingEvaluation> currentEvalBySubmission = evaluations.stream()
                .filter(e -> Integer.valueOf(1).equals(e.getIsCurrent()))
                .collect(Collectors.toMap(TeachingEvaluation::getSubmissionId, Function.identity(), (a, b) -> a));

        List<TeacherSubmissionRowVO> rows = new ArrayList<>();
        for (SysUser student : students) {
            TeacherSubmissionRowVO row = new TeacherSubmissionRowVO();
            row.setStudentId(student.getId());
            row.setStudentName(student.getRealName());
            row.setClassId(student.getClassId());
            TeachingSubmission sub = byStudent.get(student.getId());
            if (sub == null) {
                row.setState("NOT_SUBMITTED");
            } else {
                row.setSubmissionId(sub.getId());
                row.setVersionNo(sub.getVersionNo());
                row.setLate(Integer.valueOf(1).equals(sub.getLate()));
                row.setSubmitTime(sub.getUpdateTime());
                row.setState(switch (sub.getStatus()) {
                    case TeachingSubmission.STATUS_DRAFT -> "DRAFT";
                    case TeachingSubmission.STATUS_SUBMITTED -> "SUBMITTED";
                    case TeachingSubmission.STATUS_EVALUATED -> "EVALUATED";
                    case TeachingSubmission.STATUS_REVISING -> "REVISING";
                    default -> "SUBMITTED";
                });
                TeachingEvaluation eval = currentEvalBySubmission.get(sub.getId());
                if (eval != null) {
                    row.setLatestScore(eval.getScore());
                    row.setLatestDecision(eval.getDecision());
                }
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public TeacherTaskStatsVO stats(Long taskId) {
        TeachingTask task = requireTask(taskId);
        TeacherTaskStatsVO vo = new TeacherTaskStatsVO();
        vo.setTaskId(task.getId());
        vo.setTaskTitle(task.getTitle());
        vo.setStatus(task.getStatus());
        vo.setFullScore(task.getFullScore());

        List<TeacherSubmissionRowVO> rows = listSubmissions(taskId, null);
        vo.setExpectedCount(rows.size());
        int notSubmitted = 0;
        int pending = 0;
        int evaluated = 0;
        int revising = 0;
        for (TeacherSubmissionRowVO row : rows) {
            switch (row.getState()) {
                case "NOT_SUBMITTED", "DRAFT" -> notSubmitted++;
                case "SUBMITTED" -> pending++;
                case "EVALUATED" -> evaluated++;
                case "REVISING" -> revising++;
                default -> pending++;
            }
        }
        vo.setNotSubmittedCount(notSubmitted);
        vo.setPendingCount(pending);
        vo.setEvaluatedCount(evaluated);
        vo.setRevisingCount(revising);
        return vo;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 历史版本与评价都依赖任务行存在，因此任务不做物理删除。 */
    private TeachingTask requireTask(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw BusinessExceptions.badRequest("任务编号无效");
        }
        TeachingTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BusinessExceptions.notFound("任务不存在");
        }
        return task;
    }

    private List<TeachingSubmission> submissionsOf(Long taskId) {
        return submissionMapper.selectList(
                new QueryWrapper<TeachingSubmission>().eq("task_id", taskId));
    }

    private boolean isAssignedToClass(Long taskId, String classId) {
        if (classId == null || classId.isBlank()) {
            return false;
        }
        Long count = taskClassMapper.selectCount(new QueryWrapper<TeachingTaskClass>()
                .eq("task_id", taskId).eq("class_id", classId));
        return count != null && count > 0;
    }

    private Map<Long, List<String>> classIdsOf(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<TeachingTaskClass> rows = taskClassMapper.selectList(
                new QueryWrapper<TeachingTaskClass>().in("task_id", taskIds).orderByAsc("id"));
        Map<Long, List<String>> result = new java.util.LinkedHashMap<>();
        for (TeachingTaskClass row : rows) {
            result.computeIfAbsent(row.getTaskId(), k -> new ArrayList<>()).add(row.getClassId());
        }
        return result;
    }

    private Map<Long, TeachingSubmission> mySubmissions(List<Long> taskIds, String studentId) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<TeachingSubmission> rows = submissionMapper.selectList(new QueryWrapper<TeachingSubmission>()
                .in("task_id", taskIds).eq("student_id", studentId));
        return rows.stream().collect(Collectors.toMap(TeachingSubmission::getTaskId, Function.identity(), (a, b) -> a));
    }

    /** 覆盖式写入班级分配；已存在且未变化的行保持不动。 */
    private void replaceClasses(Long taskId, List<String> classIds) {
        List<TeachingTaskClass> existing = taskClassMapper.selectList(
                new QueryWrapper<TeachingTaskClass>().eq("task_id", taskId));
        Set<String> existingIds = existing.stream().map(TeachingTaskClass::getClassId)
                .collect(Collectors.toCollection(HashSet::new));
        LocalDateTime now = LocalDateTime.now();
        for (TeachingTaskClass row : existing) {
            if (!classIds.contains(row.getClassId())) {
                taskClassMapper.deleteById(row.getId());
            }
        }
        for (String classId : classIds) {
            if (!existingIds.contains(classId)) {
                TeachingTaskClass row = new TeachingTaskClass();
                row.setTaskId(taskId);
                row.setClassId(classId);
                row.setCreateTime(now);
                taskClassMapper.insert(row);
            }
        }
    }

    private TeachingTaskVO toVO(TeachingTask task, List<String> classIds, TeachingSubmission mine) {
        TeachingTaskVO vo = new TeachingTaskVO();
        vo.setId(task.getId());
        vo.setTitle(task.getTitle());
        vo.setProject(task.getProject());
        vo.setObjective(task.getObjective());
        vo.setRequirement(task.getRequirement());
        vo.setAcceptance(task.getAcceptance());
        List<String> referenceUrls = parseReferenceUrls(task.getReferenceUrl());
        vo.setReferenceUrls(referenceUrls);
        vo.setReferenceUrl(referenceUrls.isEmpty() ? null : referenceUrls.get(0));
        vo.setDeadline(task.getDeadline());
        vo.setFullScore(task.getFullScore());
        vo.setAllowLate(Integer.valueOf(1).equals(task.getAllowLate()));
        vo.setStatus(task.getStatus());
        vo.setTeacherId(task.getTeacherId());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
        vo.setClassIds(classIds);

        boolean expired = task.getDeadline() != null && LocalDateTime.now().isAfter(task.getDeadline());
        vo.setExpired(expired);
        boolean closed = Integer.valueOf(TeachingTask.STATUS_CLOSED).equals(task.getStatus());
        boolean draft = Integer.valueOf(TeachingTask.STATUS_DRAFT).equals(task.getStatus());
        vo.setSubmitAllowed(!closed && !draft && (!expired || Integer.valueOf(1).equals(task.getAllowLate())));

        if (mine != null) {
            vo.setMyStatus(mine.getStatus());
            vo.setMyVersionNo(mine.getVersionNo());
            List<TeachingEvaluation> evaluations = evaluationMapper.selectList(
                    new QueryWrapper<TeachingEvaluation>()
                            .eq("submission_id", mine.getId())
                            .eq("is_current", 1)
                            .orderByDesc("id"));
            if (!evaluations.isEmpty()) {
                TeachingEvaluation eval = evaluations.get(0);
                vo.setMyScore(eval.getScore());
                vo.setMyDecision(eval.getDecision());
            }
        }
        return vo;
    }

    // ---------------- 入参校验 ----------------

    private String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw BusinessExceptions.badRequest(field + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw BusinessExceptions.badRequest(field + "长度不能超过 " + max + " 个字符");
        }
        return trimmed;
    }

    private String optionalText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw BusinessExceptions.badRequest(field + "长度不能超过 " + max + " 个字符");
        }
        return trimmed;
    }

    private String requireProject(String project) {
        if (project == null) {
            throw BusinessExceptions.badRequest("请选择所属实训项目");
        }
        String normalized = project.trim().toUpperCase(java.util.Locale.ROOT);
        if (!TeachingTask.PROJECT_TICKET.equals(normalized)
                && !TeachingTask.PROJECT_REPAIR.equals(normalized)) {
            throw BusinessExceptions.badRequest("所属实训项目只能是 TICKET 或 REPAIR");
        }
        return normalized;
    }

    /**
     * 合并「多条列表」与「单条字段」，逐条校验只允许 http/https，最多 10 条，
     * 并以 JSON 数组形式存入 reference_url 列（该列是 varchar(500)，超出即报错提示）。
     */
    static String encodeReferenceUrls(TeachingTaskDTO dto) {
        List<String> urls = new ArrayList<>();
        if (dto.getReferenceUrls() != null) {
            for (String url : dto.getReferenceUrls()) {
                if (url != null && !url.isBlank()) {
                    urls.add(url.trim());
                }
            }
        }
        if (dto.getReferenceUrl() != null && !dto.getReferenceUrl().isBlank()) {
            String single = dto.getReferenceUrl().trim();
            if (!urls.contains(single)) {
                urls.add(single);
            }
        }
        if (urls.isEmpty()) {
            return null;
        }
        if (urls.size() > MAX_REFERENCE_URLS) {
            throw BusinessExceptions.badRequest("参考资料链接最多 " + MAX_REFERENCE_URLS + " 条");
        }
        List<String> checked = new ArrayList<>();
        for (String url : urls) {
            checked.add(requireHttpUrl(url, "参考资料链接", true));
        }
        try {
            String json = MAPPER.writeValueAsString(checked);
            if (json.length() > MAX_URL) {
                throw BusinessExceptions.badRequest(
                        "参考资料链接总长度过长，请减少条数或缩短链接（上限 " + MAX_URL + " 个字符）");
            }
            return json;
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("序列化参考资料链接失败", e);
        }
    }

    /** 解析存储值：兼容历史单条 URL 与 JSON 数组两种形态。 */
    static List<String> parseReferenceUrls(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        String trimmed = stored.trim();
        if (!trimmed.startsWith("[")) {
            return List.of(trimmed);
        }
        try {
            return MAPPER.readValue(trimmed,
                    new TypeReference<List<String>>() { });
        } catch (Exception e) {
            // 解析失败时按单条处理，避免历史数据不可读
            return List.of(trimmed);
        }
    }

    /** 只允许 http/https；服务端不会去抓取该链接。 */
    static String requireHttpUrl(String value, String field, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw BusinessExceptions.badRequest(field + "不能为空");
            }
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_URL) {
            throw BusinessExceptions.badRequest(field + "长度不能超过 " + MAX_URL + " 个字符");
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw BusinessExceptions.badRequest(field + "只支持 http 或 https 链接");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw BusinessExceptions.badRequest(field + "不是有效的网址");
            }
        } catch (IllegalArgumentException e) {
            throw BusinessExceptions.badRequest(field + "不是有效的网址");
        }
        return trimmed;
    }

    private LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw BusinessExceptions.badRequest(field + "不能为空");
        }
        try {
            // 严格解析：不存在的日期（如 2030-02-30）会在这里被拒绝
            return LocalDateTime.parse(value.trim(), TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw BusinessExceptions.badRequest(
                    field + "必须是真实存在的日期，格式为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private int requireScore(Integer fullScore) {
        if (fullScore == null) {
            throw BusinessExceptions.badRequest("满分不能为空");
        }
        if (fullScore < 1 || fullScore > MAX_FULL_SCORE) {
            throw BusinessExceptions.badRequest("满分必须在 1 到 " + MAX_FULL_SCORE + " 之间");
        }
        return fullScore;
    }

    private List<String> requireClassIds(List<String> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            throw BusinessExceptions.badRequest("请至少选择一个班级");
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(
                classIds.stream().filter(java.util.Objects::nonNull)
                        .map(String::trim).filter(s -> !s.isEmpty()).toList()));
        if (unique.isEmpty()) {
            throw BusinessExceptions.badRequest("请至少选择一个班级");
        }
        for (String classId : unique) {
            SysClass sysClass = sysClassMapper.selectById(classId);
            if (sysClass == null) {
                throw BusinessExceptions.badRequest("班级不存在：" + classId);
            }
        }
        return Collections.unmodifiableList(unique);
    }

    /** 版本表冗余校验用：确认版本确实属于该学生该任务。 */
    TeachingSubmissionVersion requireVersion(TeachingSubmission submission, Long versionId) {
        TeachingSubmissionVersion version = versionMapper.selectById(versionId);
        if (version == null || !submission.getId().equals(version.getSubmissionId())) {
            throw BusinessExceptions.notFound("提交版本不存在");
        }
        return version;
    }
}
