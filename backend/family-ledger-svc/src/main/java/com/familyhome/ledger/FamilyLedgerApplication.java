package com.familyhome.ledger;

import com.familyhome.security.ServiceSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
// 会话校验统一走 family-security：家庭编号只认这里解析出来的 SessionPrincipal。
@Import(ServiceSecurity.class)
public class FamilyLedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyLedgerApplication.class, args);
    }
}
