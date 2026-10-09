package com.study.vuePractiseBackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 教学成果截图的存储根目录。
 * 与报修图片目录（repair.files.root）完全分开，互不写入。
 */
@Data
@Component
@ConfigurationProperties(prefix = "teaching.files")
public class TeachingFileProperties {
    /** 存储根目录，相对路径按后端启动工作目录解析。 */
    private String root = "./teaching-files";
}
