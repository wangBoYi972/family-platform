package com.familyhome.user.config;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.user.mapper.HouseholdMapper;
import com.familyhome.user.mapper.PlatformInitializationMapper;
import com.familyhome.user.mapper.UserCredentialMapper;
import com.familyhome.user.mapper.WhitelistEntryMapper;
import com.familyhome.user.model.Household;
import com.familyhome.user.model.UserCredential;
import com.familyhome.user.model.WhitelistEntry;
import com.familyhome.user.security.PasswordHasher;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!local")
@ConditionalOnProperty(name = "family.bootstrap.enabled", havingValue = "true")
public class ProductionBootstrap implements ApplicationRunner {
    @Autowired private HouseholdMapper households;
    @Autowired private WhitelistEntryMapper whitelist;
    @Autowired private UserCredentialMapper credentials;
    @Autowired private PlatformInitializationMapper initialization;
    @Autowired private PasswordHasher hasher;
    @Value("${family.bootstrap.household-name:}") private String name;
    @Value("${family.bootstrap.admin-phone:}") private String phone;
    @Value("${family.bootstrap.admin-password:}") private String password;

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        // 固定行锁避免多个实例同时创建首位管理员；完成后永不重置凭据。
        if (initialization.lock()) return;
        if (name.isBlank() || name.length() > 128 || !phone.matches("^1[0-9]{10}$"))
            throw new IllegalStateException("首次初始化必须提供合法的家庭名称和管理员手机号");
        if (households.selectCount(null) != 0 || whitelist.selectCount(null) != 0)
            throw new IllegalStateException("首次初始化只允许空业务库，现有数据必须使用独立迁移流程");
        String hash = hasher.encode(password);
        var household = new Household();
        household.setName(name.trim());
        households.insert(household);
        var entry = new WhitelistEntry();
        entry.setPhone(phone);
        entry.setRole("ADMIN");
        entry.setHouseholdId(household.getId());
        entry.setEnabled(true);
        whitelist.insert(entry);
        var credential = new UserCredential();
        credential.setPhone(phone);
        credential.setPasswordHash(hash);
        credential.setChangedAt(LocalDateTime.now(ZoneOffset.UTC));
        credentials.insert(credential);
        initialization.complete();
    }
}
