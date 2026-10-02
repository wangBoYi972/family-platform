package com.familyhome.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    @Test
    @DisplayName("业务服务遵守三层、DTO/VO 和独立数据库边界")
    void enforceServiceBoundaries() throws Exception {
        Path backend = Path.of("..").toAbsolutePath().normalize();
        var failures = new ArrayList<String>();
        try (var paths = Files.walk(backend)) {
            for (Path path :
                    paths
                            .filter(
                                    p ->
                                            p.toString().contains("src")
                                                    && p.toString().contains("main")
                                                    && p.toString().endsWith(".java"))
                            .toList()) {
                String text = Files.readString(path),
                        name = path.getFileName().toString(),
                        display = backend.relativize(path).toString();
                if (text.contains("JdbcTemplate") || text.contains("JdbcOperations")
                        || text.contains("jakarta.persistence")
                        || text.contains("JpaRepository")) failures.add(display + ": 禁止旧持久化访问");
                if (Pattern.compile(
                                "@(?:org[.]apache[.]ibatis[.]annotations[.])?"
                                        + "(?:Select|Insert|Update|Delete)(?:Provider|[.]List)?\\s*[(]")
                        .matcher(text)
                        .find()) failures.add(display + ": 自定义 SQL 必须放在 resources/mapper 下的 XML 中");
                if (name.endsWith("Controller.java")) {
                    if (Pattern.compile("import .*[.](mapper|model|service[.]impl)[.]").matcher(text).find())
                        failures.add(display + ": 控制器只能使用 DTO、VO 和服务契约");
                    if (text.contains("@Transactional")
                            || text.contains("@Select")
                            || text.contains("@Update")
                            || text.contains("@Insert")) failures.add(display + ": 事务与 SQL 不得放在控制器");
                    if (text.contains("Map<String")) failures.add(display + ": 响应必须使用明确 VO");
                }
                if (name.endsWith("DTO.java")
                        && (text.contains("@TableName") || text.contains("private Long householdId")))
                    failures.add(display + ": 输入不能充当持久化实体或可信家庭上下文");
                if (name.endsWith("VO.java")
                        && (text.contains("@TableName") || text.contains("encryptedBody")))
                    failures.add(display + ": 输出不得暴露持久化模型或密文");
                if (text.contains("package com.familyhome.")
                        && display.contains("-svc")
                        && Pattern.compile("import com[.]familyhome[.][a-z]+[.]service[.]impl[.]")
                                .matcher(text)
                                .find()) failures.add(display + ": 禁止依赖实现类");
            }
        }
        try (var modules = Files.list(backend)) {
            for (Path module :
                    modules.filter(p -> p.getFileName().toString().endsWith("-svc")).toList()) {
                String pom = Files.readString(module.resolve("pom.xml"));
                if (Pattern.compile(
                                "<dependency>[^<]*<groupId>com.familyhome</groupId>"
                                        + " *<artifactId>family-[a-z]+-svc</artifactId>",
                                Pattern.DOTALL)
                        .matcher(pom)
                        .find()) failures.add(module + ": 业务服务不能相互引入实现依赖");
                if (!pom.contains("mybatis-plus-spring-boot3-starter")) failures.add(module + ": 持久化框架未统一");
            }
        }
        assertThat(failures).as("架构约束违规").isEmpty();
    }
}
