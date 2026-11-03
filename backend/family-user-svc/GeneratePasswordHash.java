import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class GeneratePasswordHash {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        String password = "123456";
        String hash = encoder.encode(password);
        System.out.println("密码: " + password);
        System.out.println("哈希: " + hash);
        System.out.println();

        // 验证
        boolean matches = encoder.matches(password, hash);
        System.out.println("验证结果: " + matches);
    }
}
