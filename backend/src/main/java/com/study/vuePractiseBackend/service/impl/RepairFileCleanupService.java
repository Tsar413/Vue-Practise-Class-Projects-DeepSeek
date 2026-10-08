package com.study.vuePractiseBackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.study.vuePractiseBackend.entity.RepairFileDeleteTask;
import com.study.vuePractiseBackend.mapper.RepairFileDeleteTaskMapper;
import com.study.vuePractiseBackend.util.RepairFileUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 图片文件清理。
 * 重置 / 删除只登记任务，任务与业务事务一起提交；
 * 后台任务先删文件再删任务，失败时保留任务下次重试。
 */
@Slf4j
@Service
public class RepairFileCleanupService {

    @Resource
    private RepairFileDeleteTaskMapper taskMapper;

    @Resource
    private RepairFileUtil fileUtil;

    /** 必须参与调用方事务：回滚时任务一起回滚，原图片保留。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Collection<String> paths) {
        for (String path : paths.stream()
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .toList()) {
            RepairFileDeleteTask task = new RepairFileDeleteTask();
            task.setImageUrl(path);
            task.setCreateTime(LocalDateTime.now());
            if (taskMapper.insert(task) != 1) {
                throw new IllegalStateException("创建图片清理任务失败");
            }
        }
    }

    /** 每 30 秒处理最多 100 条待清理任务。 */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void clean() {
        try {
            var tasks = taskMapper.selectList(new LambdaQueryWrapper<RepairFileDeleteTask>()
                    .orderByAsc(RepairFileDeleteTask::getId)
                    .last("LIMIT 100"));
            for (RepairFileDeleteTask task : tasks) {
                try {
                    fileUtil.delete(task.getImageUrl());
                    taskMapper.deleteById(task.getId());
                } catch (RuntimeException e) {
                    log.warn("图片清理失败，保留任务待重试：{}", task.getId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.error("读取图片清理任务失败", e);
        }
    }
}
