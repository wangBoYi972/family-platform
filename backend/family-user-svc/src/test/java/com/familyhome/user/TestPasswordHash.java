package com.familyhome.user;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.familyhome.user.security.PasswordHasher;

@SpringBootTest
public class TestPasswordHash {
    @Autowired
    private PasswordHasher hasher;
    
    @Test
    public void generateHash() {
        String password = "123456";
        String hash = hasher.encode(password);
        System.out.println("密码: " + password);
        System.out.println("哈希: " + hash);
        System.out.println("验证: " + hasher.matches(password, hash));
    }
}
