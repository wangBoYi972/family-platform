package com.familyhome.media;

import com.familyhome.security.ServiceSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ServiceSecurity.class)
public class FamilyMediaApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyMediaApplication.class, args);
    }
}
