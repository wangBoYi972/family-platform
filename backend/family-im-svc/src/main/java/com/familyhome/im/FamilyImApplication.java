package com.familyhome.im;

import com.familyhome.security.ServiceSecurity;
import com.familyhome.messaging.MessagingRabbitConfiguration;
import com.familyhome.messaging.OutboxEventStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ServiceSecurity.class, MessagingRabbitConfiguration.class, OutboxEventStore.class})
public class FamilyImApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyImApplication.class, args);
    }
}
