package com.study.vuePractiseBackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启定时任务，用于重试清理已删除工单遗留的图片文件。 */
@Configuration
@EnableScheduling
public class RepairSchedulingConfig {
}
