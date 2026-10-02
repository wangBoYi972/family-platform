package com.familyhome.archive;

import com.familyhome.security.ServiceSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ServiceSecurity.class)
public class FamilyArchiveApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyArchiveApplication.class, args);
    }
}
