package com.familyhome.content;

import com.familyhome.security.ServiceSecurity;
import com.familyhome.messaging.MessagingRabbitConfiguration;
import com.familyhome.messaging.OutboxEventStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// Feign 客户端由 ServiceSecurity -> ServiceClients 统一装配（contextId 固定）。
// 这里再声明 @EnableFeignClients(basePackages="com.familyhome.api") 会把同一个
// contextId 注册两次，启动直接抛 BeanDefinitionOverrideException。
@Import({ServiceSecurity.class, MessagingRabbitConfiguration.class, OutboxEventStore.class})
public class FamilyContentApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyContentApplication.class, args);
    }
}
