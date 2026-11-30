package com.familyhome.user;

import com.familyhome.security.ServiceSecurity;
import com.familyhome.user.config.AuthProperties;
import com.familyhome.user.config.WxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, WxProperties.class})
@Import(ServiceSecurity.class)
public class FamilyUserApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyUserApplication.class, args);
    }
}
