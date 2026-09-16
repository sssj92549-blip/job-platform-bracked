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

    @org.junit.jupiter.api.Test
    void filtersConfirmedCandidateFieldsInMySql() throws Exception {
        var j=job(); var r=resume();
        var confirmed=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(r.getConfirmedProfile());
        confirmed.put("birthDate",BusinessSupport.now().toLocalDate().minusYears(28).toString()); confirmed.put("workExperienceYears",4);
        r.setConfirmedProfile(confirmed.toString()); resumes.updateById(r);
        send("POST","/api/applications",seeker,new cn.itcast.demo.jobplatform.dto.RecruitmentRequests.Apply(j.getId(),r.getId(),1)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        String path="/api/company/applications?jobId="+j.getId();
        for(String filter:List.of("education=HIGH_SCHOOL","education=JUNIOR_COLLEGE","education=BACHELOR","experience=3_5","ageMin=28","ageMax=28")) send("GET",path+"&"+filter,company,null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(1));
        send("GET",path+"&education=OTHER",company,null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        send("GET",path+"&education=BACHELOR&experience=3_5&ageMin=28&ageMax=28",company,null)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.records[0].candidateAge").value(28));
        send("GET",path+"&education=MASTER",company,null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(0));
        send("GET",path+"&experience=1_3",company,null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(0));
        send("GET",path+"&ageMin=29",company,null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(0));
        send("GET",path+"&ageMin=40&ageMax=20",company,null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        send("GET",path+"&experience=3_5",other,null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(0));
    }

    @org.junit.jupiter.api.Test
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentInvitationSendsAndResponsesHaveOneWinner() throws Exception {
        var j=job(); var r=resume();
        var body=Map.of("candidateId",seeker.getId(),"resumeId",r.getId(),"resumeVersion",1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var gate=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> sending=()-> { gate.await(); return send("POST","/api/company/jobs/"+j.getId()+"/application-invitations",company,body).andReturn().getResponse().getStatus(); };
            var first=pool.submit(sending); var second=pool.submit(sending); gate.countDown();
            org.assertj.core.api.Assertions.assertThat(List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            var aid=data(send("POST","/api/applications",seeker,new cn.itcast.demo.jobplatform.dto.RecruitmentRequests.Apply(j.getId(),r.getId(),1))).path("id").asText();
            var id=data(send("POST","/api/company/applications/"+aid+"/interview-invitations",company,Map.of("interviewAt",BusinessSupport.now().plusDays(2).atOffset(java.time.ZoneOffset.ofHours(8)).toString(),"interviewMode","ONLINE","location","视频会议室"))).path("id").asText();
            var respondGate=new java.util.concurrent.CountDownLatch(1);
            var accept=pool.submit(()-> { respondGate.await(); return send("POST","/api/invitations/"+id+"/respond",seeker,Map.of("action","ACCEPT")).andReturn().getResponse().getStatus(); });
            var reject=pool.submit(()-> { respondGate.await(); return send("POST","/api/invitations/"+id+"/respond",seeker,Map.of("action","REJECT")).andReturn().getResponse().getStatus(); });
            respondGate.countDown();
            org.assertj.core.api.Assertions.assertThat(List.of(accept.get(10,java.util.concurrent.TimeUnit.SECONDS),reject.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from notification where invitation_id=? and recipient_id=?",Long.class,Long.valueOf(id),company.getId())).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
            jdbc.update("delete from notification where invitation_id in (select id from recruitment_invitation where job_id=?)",j.getId());
            jdbc.update("delete from recruitment_invitation where job_id=?",j.getId());
            jdbc.update("delete from ai_task where job_id=?",j.getId());
            jdbc.update("delete from application where job_id=?",j.getId());
            jdbc.update("delete from job where id=?",j.getId());
            jdbc.update("delete from resume where id=?",r.getId());
        }
    }
}
