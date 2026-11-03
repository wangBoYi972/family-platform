package com.familyhome.ai;

import com.familyhome.security.ServiceSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

import com.familyhome.ai.config.AiProperties;

/**
 * AI 家庭助手服务启动类
 *
 * <p>提供智能对话服务，支持调用家庭数据查询工具(任务、日程、档案、文件等)。
 * 使用 DashScope(OpenAI 兼容接口)进行流式对话，支持工具调用循环。
 *
 * @author Family Platform Team
 * @since 1.0.0
 */
@SpringBootApplication
@EnableConfigurationProperties(AiProperties.class)
@EnableFeignClients
@Import(ServiceSecurity.class)
public class FamilyAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyAiApplication.class, args);
    }
}
