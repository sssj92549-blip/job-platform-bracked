package cn.itcast.demo.jobplatform.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** 在随机临时MySQL库重跑招聘流程，使用正式init.sql验证JSON、枚举和生成列。 */
@EnabledIfSystemProperty(named="recruitment.mysql.tests",matches="true")
class RecruitmentMySqlIntegrationTests extends RecruitmentIntegrationTests {
    private static final String DATABASE="job_platform_test_"+UUID.randomUUID().toString().replace("-","");
    private static String password;
    private static String username;
    private static final String SERVER="jdbc:mysql://127.0.0.1:3306/";
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        // 本地配置只在内存读取，永不打印或写入测试报告。
        Map<?,?> local=new Yaml().load(Files.readString(Path.of("src/main/resources/application-local.yml")));
        Map<?,?> ds=(Map<?,?>)((Map<?,?>)local.get("spring")).get("datasource");
        username=ds.containsKey("username")?ds.get("username").toString():"root";
        password=ds.get("password").toString();
        try(Connection connection=DriverManager.getConnection(SERVER,username,password)) {
            String sql=Files.readString(Path.of("docs/init.sql")).replace("job_platform",DATABASE);
            ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
        properties.add("spring.datasource.url",()->SERVER+DATABASE+"?serverTimezone=Asia/Shanghai");
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        properties.add("spring.datasource.username",()->username);
        properties.add("spring.datasource.password",()->password);
        properties.add("spring.sql.init.mode",()->"never");
    }
    @AfterAll static void cleanup() throws Exception {
        if(password==null) return;
        if(!DATABASE.matches("job_platform_test_[a-f0-9]{32}")) throw new IllegalStateException("Invalid test database");
        try(Connection connection=DriverManager.getConnection(SERVER,username,password); Statement statement=connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `"+DATABASE+"`");
        }
    }
}
