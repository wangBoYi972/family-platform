package com.familyhome.user.config;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.user.mapper.HouseholdMapper;
import com.familyhome.user.mapper.UserCredentialMapper;
import com.familyhome.user.mapper.WhitelistEntryMapper;
import com.familyhome.user.model.Household;
import com.familyhome.user.model.UserCredential;
import com.familyhome.user.model.WhitelistEntry;
import com.familyhome.user.security.PasswordHasher;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地开发引导：仅在 local profile 且空库时初始化一个管理员白名单账号及密码凭据，
 * 使管理端与两个小程序的口令登录开箱可用。生产环境由 {@link ProductionBootstrap} 处理。
 */
@Component
@Profile("local")
public class LocalBootstrap implements ApplicationRunner {
    @Autowired private HouseholdMapper households;
    @Autowired private WhitelistEntryMapper whitelist;
    @Autowired private UserCredentialMapper credentials;
    @Autowired private PasswordHasher hasher;
    @Value("${FAMILY_LOCAL_ADMIN_PHONE:19900000001}") private String phone;
    @Value("${FAMILY_LOCAL_ADMIN_PASSWORD:local-admin-123456}") private String password;

    @Transactional
    public void run(ApplicationArguments args) {
        if (households.selectCount(null) == 0) {
            var h = new Household();
            h.setName("本地测试家庭");
            households.insert(h);
            var w = new WhitelistEntry();
            w.setPhone(phone);
            w.setHouseholdId(h.getId());
            w.setRole("ADMIN");
            w.setEnabled(true);
            whitelist.insert(w);
        }
        if (credentials.selectById(phone) == null) {
            var c = new UserCredential();
            c.setPhone(phone);
            c.setPasswordHash(hasher.encode(password));
            c.setFailedAttempts(0);
            c.setChangedAt(LocalDateTime.now());
            credentials.insert(c);
        }
    }
}
