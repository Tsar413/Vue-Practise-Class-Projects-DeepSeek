package com.study.vuePractiseBackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "repair.files")
public class RepairFileProperties {
    /** 图片存储根目录，相对路径按后端启动工作目录解析。 */
    private String root = "./repair-files";
}
