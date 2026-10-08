package com.study.vuePractiseBackend;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/** 保留外置 Tomcat 部署能力，与参考项目一致。 */
public class ServletInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(StudyVuePractiseBackendApplication.class);
    }
}
