package com.familyhome.content;

import com.familyhome.security.ServiceSecurity;
import com.familyhome.messaging.MessagingRabbitConfiguration;
import com.familyhome.messaging.OutboxEventStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ServiceSecurity.class, MessagingRabbitConfiguration.class, OutboxEventStore.class})
public class FamilyContentApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyContentApplication.class, args);
    }
}
