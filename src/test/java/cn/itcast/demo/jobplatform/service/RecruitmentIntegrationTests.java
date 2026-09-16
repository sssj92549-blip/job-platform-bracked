package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import cn.itcast.demo.jobplatform.dto.RecruitmentRequests.*;
import com.fasterxml.jackson.databind.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpMethod;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

/** 真实MVC/Service/Mapper/事务，外部Python与Redis在此隔离；Redis另有真实集成验证。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RecruitmentIntegrationTests {
    private static final Path TEST_FILES;
    static { try { TEST_FILES=Files.createTempDirectory("job-platform-test-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @org.springframework.test.context.DynamicPropertySource
    static void storage(org.springframework.test.context.DynamicPropertyRegistry properties) { properties.add("app.storage.root",()->TEST_FILES.toString()); }
    @AfterAll static void removeTestFiles() throws Exception {
        if(!TEST_FILES.getFileName().toString().startsWith("job-platform-test-")) throw new IllegalStateException("Invalid test path");
        if(Files.exists(TEST_FILES)) try(var paths=Files.walk(TEST_FILES)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountMapper accounts;
    @Autowired ProfileRepository profiles;
    @Autowired JobMapper jobs;
    @Autowired JobVectorService jobVectors;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired ResumeMapper resumes;
    @Autowired ApplicationMapper applications;
    @Autowired AiTaskMapper tasks;
    @Autowired VectorSyncTaskMapper vectorTasks;
    @Autowired ResumeJobMatchMapper matches;
    @Autowired ResumeService resumeService;
    @Autowired AiTaskService ai;
    @Autowired VectorSyncService vectors;
    @MockitoBean LoginGuard loginGuard;
    @MockitoBean BusinessRedis redis;
    @MockitoBean PythonAiClient python;
    Profile seeker,company,other,admin;
    @BeforeEach void setup() {
        seeker=profile("JOB_SEEKER"); company=profile("COMPANY"); other=profile("COMPANY"); admin=profile("ADMIN");
        when(redis.lock(anyString())).thenReturn("test-lock");
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/conversation-query"),any())).thenAnswer(call->{
            JsonNode input=call.getArgument(2); return plan(json.createObjectNode().put("query",input.path("question").asText()));
        });
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/review-job-candidates"),any())).thenAnswer(call->{
            JsonNode input=call.getArgument(2); var decisions=json.createArrayNode();
            for(JsonNode item:input.path("jobs")) decisions.addObject().put("jobId",item.path("jobId").asText()).put("eligible",true).put("reason","符合本轮条件");
            return json.createObjectNode().set("decisions",decisions);
        });
    }
    private JsonNode plan(JsonNode values) {
        var plan=json.createObjectNode().put("intent",values.path("referencedJobIds").isEmpty()?"SEARCH":"REFERENCES").put("resumeMode","MATCH").put("excludeSeen",false);
        plan.putArray("requirements"); plan.putArray("exclusions"); plan.putArray("referencedJobIds");
        plan.setAll((com.fasterxml.jackson.databind.node.ObjectNode)values); return plan;
    }
    Profile profile(String role) {
        Account a=new Account(); a.setUsername("u"+UUID.randomUUID().toString().replace("-","").substring(0,20)); a.setPhone("1"+String.format("%010d",Math.abs(UUID.randomUUID().getLeastSignificantBits()%10000000000L))); a.setPasswordHash("test-no-login"); a.setEnabled(true); accounts.insert(a);
        Profile p=new Profile(); p.setAccountId(a.getId()); p.setRole(role); p.setName("测试用户"); p.setEducation("BACHELOR"); p.setEnabled(true); p.setDiscoverable(true); if(!role.equals("JOB_SEEKER")) p.setDiscoverable(false); p.setReviewStatus("APPROVED"); if(role.equals("COMPANY")) p.setCompanyName("测试企业"); profiles.insert(p); return p;
    }
    MockHttpSession session(Profile p) { MockHttpSession s=new MockHttpSession(); s.setAttribute(SessionSupport.ACCOUNT,p.getAccountId()); s.setAttribute(SessionSupport.PROFILE,p.getId()); s.setAttribute(SessionSupport.CSRF,"csrf-test"); return s; }
    MockHttpServletRequest request(Profile p) { var r=new MockHttpServletRequest(); r.setSession(session(p)); return r; }
    ResultActions send(String method,String path,Profile p,Object data) throws Exception {
        var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path).session(session(p)).header("X-CSRF-Token","csrf-test").header("X-Profile-Id",p.getId());
        if(data!=null) req.contentType("application/json").content(json.writeValueAsBytes(data)); return mvc.perform(req);
    }
    JsonNode data(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsByteArray()).path("data"); }
    Job job() {
        Job j=new Job(); j.setCompanyId(company.getId()); j.setTitle("Java开发"); j.setCity("杭州"); j.setSalaryMin(8000); j.setSalaryMax(15000); j.setExperienceMinYears(0); j.setDescription("开发招聘平台"); j.setRequirements("熟悉Java"); j.setSkills("[\"Java\"]"); j.setStatus("APPROVED"); j.setVersion(1); j.setDeleted(false); jobs.insert(j); return j;
    }
    Resume resume() {
        Resume r=new Resume(); r.setCandidateId(seeker.getId()); r.setVersion(1); r.setIsCurrent(true); r.setDeleted(false); r.setFileName("cv.pdf"); r.setFilePath("resumes/test.pdf"); r.setFileSize(100L); r.setParseStatus("SUCCESS"); r.setConfirmationStatus("CONFIRMED"); r.setConflictStatus("NONE"); r.setIndexStatus("READY"); r.setExtractedText("熟悉Java和MySQL，有招聘平台开发经验"); r.setOriginalProfile("{}"); r.setConfirmedProfile("{\"name\":\"测试用户\",\"contactPhone\":\"13800138000\",\"education\":\"BACHELOR\",\"skills\":[\"Java\"]}"); r.setParsedSkills("[\"Java\"]"); r.setConflicts("[]"); r.setConfirmedAt(BusinessSupport.now()); resumes.insert(r); return r;
    }
    @Test void jobLifecycleOwnershipAndPublicCacheCannotExposeClosedJob() throws Exception {
        var input=new JobInput("Java开发","杭州",8000,15000,null,0,"参与开发","熟悉Java",List.of("Java"));
        String id=data(send("POST","/api/company/jobs",company,input).andExpect(status().isCreated())).path("id").asText();
        send("PUT","/api/company/jobs/"+id,other,input).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/"+id)).andExpect(status().isNotFound());
        send("POST","/api/company/jobs/"+id+"/publish",company,null).andExpect(status().isConflict());
        company.setIndustry("互联网"); company.setCompanySize("100_499"); company.setCity("杭州"); company.setCompanyDescription("企业软件开发"); profiles.updateById(company);
        send("POST","/api/company/jobs/"+id+"/publish",other,null).andExpect(status().isNotFound());
        send("POST","/api/company/jobs/"+id+"/publish",company,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
        JsonNode publicJob=data(mvc.perform(get("/api/jobs/"+id)).andExpect(status().isOk()));
        when(redis.cachedJob(Long.valueOf(id),1)).thenReturn(publicJob.toString());
        send("PUT","/api/company/jobs/"+id,company,input).andExpect(status().isConflict());
        send("POST","/api/company/jobs/"+id+"/close",company,null).andExpect(status().isOk());
        mvc.perform(get("/api/jobs/"+id)).andExpect(status().isNotFound());
    }
    @Test void jobVectorLifecycleRetriesAndCloses() throws Exception {
        Job j=job(); jobVectors.enqueue(j,false);
        when(python.call(eq(HttpMethod.PUT),contains("/internal/vector/jobs/"),any())).thenReturn(json.readTree("{\"indexed\":true}"));
        jobVectors.processOne(); assertThat(jobVectors.latest(j.getId()).get("status")).isEqualTo("SUCCESS");
        send("POST","/api/company/jobs/"+j.getId()+"/close",company,null).andExpect(status().isOk());
        when(python.call(eq(HttpMethod.DELETE),contains("/internal/vector/jobs/"),isNull())).thenReturn(json.readTree("{\"deleted\":true}"));
        jobVectors.processOne(); assertThat(jobVectors.latest(j.getId()).get("operation")).isEqualTo("DELETE");
        assertThat(jobVectors.latest(j.getId()).get("status")).isEqualTo("SUCCESS");
        jdbc.update("UPDATE job_vector_task SET status='FAILED' WHERE id=?",jobVectors.latest(j.getId()).get("id"));
        send("POST","/api/company/jobs/"+j.getId()+"/index-retry",other,null).andExpect(status().isNotFound());
        send("POST","/api/company/jobs/"+j.getId()+"/index-retry",company,null).andExpect(status().isAccepted());
        assertThat(jobVectors.latest(j.getId()).get("status")).isEqualTo("PENDING");
    }
    @Test void recommendedJobsRequireOwnConfirmedResumeAndKeepCityFilter() throws Exception {
        Job j=job();
        send("GET","/api/jobs?mode=recommended",seeker,null).andExpect(status().isConflict());
        resume();
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(json.readTree("{\"matches\":[{\"jobId\":\""+j.getId()+"\",\"jobVersion\":1,\"similarity\":0.8}]}"));
        send("GET","/api/jobs?mode=recommended",seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        send("GET","/api/jobs?mode=recommended&city=北京",seeker,null).andExpect(jsonPath("$.data.total").value(0));
        send("GET","/api/jobs?mode=recommended",company,null).andExpect(status().isForbidden());
    }
    @Test void recommendationsKeepAllRelevantJobsWithoutFixedCount() throws Exception {
        resume();
        var retrieved=json.createArrayNode();
        for(int i=0;i<25;i++) {
            Job j=job();
            retrieved.addObject().put("jobId",j.getId().toString()).put("jobVersion",1).put("similarity",0.85);
        }
        Job weak=job();
        retrieved.addObject().put("jobId",weak.getId().toString()).put("jobVersion",1).put("similarity",0.68);
        var result=json.createObjectNode().set("matches",retrieved);
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(result);
        send("GET","/api/jobs?mode=recommended",seeker,null).andExpect(jsonPath("$.data.total").value(25));
    }
    @Test void ragAssistantCitesRetrievedJobAndMarksLaterClosure() throws Exception {
        Job j=job(); Resume r=resume();
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(json.readTree("{\"matches\":[{\"jobId\":\""+j.getId()+"\",\"jobVersion\":1,\"similarity\":0.8}]}"));
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/recommendation-answer"),any())).thenReturn(json.readTree("{\"answer\":\"可以关注以下职位\",\"recommendations\":[{\"jobId\":\""+j.getId()+"\",\"reason\":\"Java技能符合要求\"}]}"));
        String id=data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"我适合什么岗位"))).path("id").asText();
        ai.processOne();
        send("GET","/api/ai/tasks/"+id,seeker,null).andExpect(jsonPath("$.data.status").value("SUCCESS")).andExpect(jsonPath("$.data.result.sources[0].jobId").value(j.getId().toString())).andExpect(jsonPath("$.data.result.sources[0].available").value(true));
        j.setStatus("CLOSED"); jobs.updateById(j);
        send("GET","/api/ai/tasks/"+id,seeker,null).andExpect(jsonPath("$.data.result.sources[0].available").value(false));
        assertThat(matches.selectCount(null)).isZero();
    }
    @Test void conversationUsesOwnedHistoryResolvesReferencesAndRestoresTurns() throws Exception {
        Job j=job(); Resume r=resume();
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(json.readTree("{\"matches\":[{\"jobId\":\""+j.getId()+"\",\"jobVersion\":1,\"similarity\":0.8}]}"));
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/recommendation-answer"),any())).thenReturn(json.readTree("{\"answer\":\"推荐Java职位\",\"recommendations\":[{\"jobId\":\""+j.getId()+"\",\"reason\":\"技能相符\"}]}"));
        Long first=Long.valueOf(data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"推荐Java岗位"))).path("id").asText());
        send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"哪个薪资高",first)).andExpect(status().isConflict());
        ai.processOne();
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/conversation-query"),any())).thenReturn(plan(json.readTree("{\"query\":\"比较推荐的Java岗位薪资\",\"referencedJobIds\":[\""+j.getId()+"\"]}")));
        Long second=Long.valueOf(data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"这些哪个薪资高",first))).path("id").asText());
        ai.processOne();
        send("GET","/api/ai/tasks/"+second+"/conversation",seeker,null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(2))
            .andExpect(jsonPath("$.data.records[0].question").value("推荐Java岗位"))
            .andExpect(jsonPath("$.data.records[1].status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.records[1].previousTaskId").value(first.toString()));
        verify(python).call(eq(HttpMethod.POST),eq("/internal/ai/conversation-query"),argThat(n->n instanceof JsonNode x && x.path("history").path(0).path("answer").asText().equals("推荐Java职位")));
        verify(python,times(1)).call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any());
        Profile stranger=profile("JOB_SEEKER");
        send("GET","/api/ai/tasks/"+second+"/conversation",stranger,null).andExpect(status().isNotFound());
        AiTask foreign=new AiTask(); foreign.setCreatorId(stranger.getId()); foreign.setType("ASSISTANT"); foreign.setStatus("FAILED"); foreign.setQuestion("私密问题"); foreign.setResumeId(r.getId()); foreign.setResumeVersion(1); foreign.setRequestKey("foreign-chat-test"); foreign.setInputSnapshot("{}"); tasks.insert(foreign);
        send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"继续",foreign.getId())).andExpect(status().isNotFound());
        // 历史引用下架后不可重新推荐。
        j.setStatus("CLOSED"); jobs.updateById(j);
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/recommendation-answer"),any())).thenReturn(json.readTree("{\"answer\":\"该职位已不可用\",\"recommendations\":[]}"));
        Long third=Long.valueOf(data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"这个还在招吗",second))).path("id").asText()); ai.processOne();
        send("GET","/api/ai/tasks/"+third,seeker,null).andExpect(jsonPath("$.data.status").value("SUCCESS")).andExpect(jsonPath("$.data.result.sources").isEmpty());
        assertThat(tasks.selectById(third).getResult()).contains("REFERENCES");
        r.setVersion(2); resumes.updateById(r);
        send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),2,null,"继续",second)).andExpect(status().isConflict());
    }
    @Test void conversationRejectsInventedReferencesAndRetrievesRewrittenQuestion() throws Exception {
        Job j=job(); Resume r=resume();
        AiTask parent=new AiTask(); parent.setCreatorId(seeker.getId()); parent.setType("ASSISTANT"); parent.setStatus("SUCCESS"); parent.setResumeId(r.getId()); parent.setResumeVersion(1); parent.setQuestion("推荐Java岗位"); parent.setResult("{\"answer\":\"暂未找到\",\"sources\":[]}"); parent.setInputSnapshot("{}"); parent.setRequestKey("parent-chat-test"); parent.setCompletedAt(BusinessSupport.now()); tasks.insert(parent);
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/conversation-query"),any())).thenReturn(plan(json.readTree("{\"query\":\"杭州Java岗位\",\"referencedJobIds\":[]}")));
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(json.readTree("{\"matches\":[]}"));
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/recommendation-answer"),any())).thenReturn(json.readTree("{\"answer\":\"暂未找到\",\"recommendations\":[]}"));
        send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"只看杭州的",parent.getId())).andExpect(status().isAccepted()); ai.processOne();
        verify(python).call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),argThat(n->n instanceof JsonNode x && x.path("queryText").asText().equals("杭州Java岗位")));
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/conversation-query"),any())).thenReturn(plan(json.readTree("{\"query\":\"其他职位\",\"referencedJobIds\":[\""+j.getId()+"\"]}")));
        String id=data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"第二个呢",parent.getId()))).path("id").asText(); ai.processOne();
        send("GET","/api/ai/tasks/"+id,seeker,null).andExpect(jsonPath("$.data.status").value("FAILED"));
    }
    @Test void intentSearchIgnoresResumeAndChecksBeyondFirstCandidateBatch() throws Exception {
        Resume r=resume();
        for(int i=0;i<13;i++) job();
        Job target=job(); target.setTitle("客户服务专员"); target.setRequirements("沟通和问题记录"); jobs.updateById(target);
        var searchPlan=(com.fasterxml.jackson.databind.node.ObjectNode)plan(json.createObjectNode().put("query","客户服务等通用岗位"));
        searchPlan.put("resumeMode","IGNORE"); searchPlan.withArray("exclusions").add("软件开发方向");
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/conversation-query"),any())).thenReturn(searchPlan);
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(json.readTree("{\"matches\":[]}"));
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/review-job-candidates"),any())).thenAnswer(call->{
            JsonNode input=call.getArgument(2); assertThat(input.has("resumeText")).isFalse();
            var decisions=json.createArrayNode();
            for(JsonNode item:input.path("jobs")) decisions.addObject().put("jobId",item.path("jobId").asText()).put("eligible",item.path("jobId").asText().equals(target.getId().toString())).put("reason","审核当前职责与排除方向");
            return json.createObjectNode().set("decisions",decisions);
        });
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/recommendation-answer"),any())).thenAnswer(call->{
            JsonNode input=call.getArgument(2); assertThat(input.has("resumeText")).isFalse(); assertThat(input.path("jobs").size()).isEqualTo(1);
            var out=json.createObjectNode().put("answer","可以了解客户服务岗位，按职位要求准备。"); out.putArray("recommendations").addObject().put("jobId",target.getId().toString()).put("reason","符合当前职业方向"); return out;
        });
        String id=data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"不按简历，换个方向"))).path("id").asText(); ai.processOne();
        send("GET","/api/ai/tasks/"+id,seeker,null).andExpect(jsonPath("$.data.status").value("SUCCESS")).andExpect(jsonPath("$.data.result.sources[0].jobId").value(target.getId().toString())).andExpect(jsonPath("$.data.result.retrieval.reviewedCount").value(14));
        verify(python,times(2)).call(eq(HttpMethod.POST),eq("/internal/ai/review-job-candidates"),any());
        verify(python).call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),argThat(n->n instanceof JsonNode x && !x.has("resumeText") && x.path("minSimilarity").asDouble()==0.0));
        String follow=data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"继续找",Long.valueOf(id)))).path("id").asText();
        assertThat(json.readTree(tasks.selectById(follow).getInputSnapshot()).path("previousPlan").path("resumeMode").asText()).isEqualTo("IGNORE");
    }
    @Test void generalCareerAdviceDoesNotForceJobRetrieval() throws Exception {
        Resume r=resume(); var advice=(com.fasterxml.jackson.databind.node.ObjectNode)plan(json.createObjectNode().put("query","职业准备建议")); advice.put("intent","ADVICE"); advice.put("resumeMode","CONTEXT");
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/conversation-query"),any())).thenReturn(advice);
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/recommendation-answer"),any())).thenReturn(json.readTree("{\"answer\":\"先梳理兴趣和可迁移能力。\",\"recommendations\":[]}"));
        String id=data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"如何规划职业转型"))).path("id").asText(); ai.processOne();
        send("GET","/api/ai/tasks/"+id,seeker,null).andExpect(jsonPath("$.data.status").value("SUCCESS"));
        verify(python,never()).call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any());
        verify(python,never()).call(eq(HttpMethod.POST),eq("/internal/ai/review-job-candidates"),any());
    }
    @Test void malformedCandidateReviewCannotFallBackToUnfilteredJobs() throws Exception {
        Job j=job(); Resume r=resume();
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(json.readTree("{\"matches\":[]}"));
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/review-job-candidates"),any())).thenReturn(json.readTree("{\"decisions\":[]}"));
        String id=data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"找满足条件的岗位"))).path("id").asText(); ai.processOne();
        send("GET","/api/ai/tasks/"+id,seeker,null).andExpect(jsonPath("$.data.status").value("FAILED"));
        verify(python,never()).call(eq(HttpMethod.POST),eq("/internal/ai/recommendation-answer"),any());
    }
    @Test void remoteRagDoesNotInventRemoteJobs() throws Exception {
        job(); Resume r=resume();
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/review-job-candidates"),any())).thenAnswer(call->{
            JsonNode input=call.getArgument(2); var decisions=json.createArrayNode();
            for(JsonNode item:input.path("jobs")) decisions.addObject().put("jobId",item.path("jobId").asText()).put("eligible",false).put("reason","没有明确远程依据");
            return json.createObjectNode().set("decisions",decisions);
        });
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(json.readTree("{\"matches\":[]}"));
        String id=data(send("POST","/api/ai/assistant",seeker,new AiInput(null,r.getId(),1,null,"有没有远程岗位"))).path("id").asText(); ai.processOne();
        send("GET","/api/ai/tasks/"+id,seeker,null).andExpect(jsonPath("$.data.status").value("SUCCESS")).andExpect(jsonPath("$.data.result.sources").isEmpty());
        verify(python,never()).call(eq(HttpMethod.POST),eq("/internal/ai/recommendation-answer"),any());
    }
    @Test void selectedIndustrySearchesJobsAcrossCompanyIndustries() throws Exception {
        Job j=job(); company.setIndustry("汽车制造"); profiles.updateById(company);
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),argThat(n->n instanceof JsonNode node && node.path("queryText").asText().equals("互联网")))).thenReturn(json.readTree("{\"matches\":[{\"jobId\":\""+j.getId()+"\",\"jobVersion\":1,\"similarity\":0.8}]}"));
        send("GET","/api/jobs?industry=互联网",seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        send("GET","/api/jobs?industry=互联网&city=北京",seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        send("GET","/api/jobs?industry=互联网&keyword=不存在的职位",seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        send("GET","/api/jobs?industry=互联网&keyword=Java",seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }
    @Test void hybridSearchUsesSemanticMatchesAndRetainsFilters() throws Exception {
        Job j=job(); j.setTitle("后端工程师"); j.setRequirements("熟悉事务与接口设计"); jobs.updateById(j);
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(json.readTree("{\"matches\":[{\"jobId\":\""+j.getId()+"\",\"jobVersion\":1,\"similarity\":0.8},{\"jobId\":\"999\",\"jobVersion\":1,\"similarity\":1}]}"));
        send("GET","/api/jobs?keyword=计算机",seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        send("GET","/api/jobs?keyword=计算机&city=北京",seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        j.setStatus("CLOSED"); jobs.updateById(j);
        send("GET","/api/jobs?keyword=计算机",seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        j.setStatus("APPROVED"); jobs.updateById(j);
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenThrow(new RuntimeException("offline"));
        send("GET","/api/jobs?keyword=后端",seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }
    @Test void applicationAutomaticallyScoresOnceWithoutBlockingSubmission() throws Exception {
        Job j=job(); Resume r=resume();
        send("POST","/api/applications",seeker,new Apply(j.getId(),r.getId(),1)).andExpect(status().isCreated());
        assertThat(tasks.selectCount(null)).isEqualTo(1);
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/match"),any())).thenReturn(json.readTree("{\"score\":86,\"reasons\":[\"skills match\"],\"gaps\":[]}"));
        ai.processOne(); ai.processOne();
        send("GET","/api/company/applications?jobId="+j.getId(),company,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].matchScore").value(86));
        verify(python,times(1)).call(eq(HttpMethod.POST),eq("/internal/ai/match"),any());
    }
    @Test void applicationRequiresVersionPreservesSnapshotAndRejectsDuplicateOrOtherCompany() throws Exception {
        Job j=job(); Resume r=resume();
        send("POST","/api/applications",seeker,new Apply(j.getId(),r.getId(),99)).andExpect(status().isConflict());
        String id=data(send("POST","/api/applications",seeker,new Apply(j.getId(),r.getId(),1)).andExpect(status().isCreated())).path("id").asText();
        send("GET","/api/applications/"+id,other,null).andExpect(status().isNotFound());
        send("POST","/api/applications",seeker,new Apply(j.getId(),r.getId(),1)).andExpect(status().isConflict());
        send("PATCH","/api/company/applications/"+id+"/profile-source",company,new Source("AI")).andExpect(status().isOk());
        assertThat(profiles.selectById(seeker.getId()).getName()).isEqualTo("测试用户");
        send("POST","/api/applications/"+id+"/withdraw",seeker,null).andExpect(status().isOk());
        send("PATCH","/api/company/applications/"+id+"/status",company,new ApplicationStatus("VIEWED")).andExpect(status().isConflict());
        send("POST","/api/applications",seeker,new Apply(j.getId(),r.getId(),1)).andExpect(status().isConflict());
    }
    @Test void confirmUsesExpectedVersionAndAutomaticallyFillsTimestamps() throws Exception {
        Resume r=resume();
        var input=new Confirm(1,"确认姓名","13800138000","MASTER",List.of("Java"));
        send("POST","/api/resumes/"+r.getId()+"/confirm",seeker,input).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        send("POST","/api/resumes/"+r.getId()+"/confirm",seeker,input).andExpect(status().isConflict());
        assertThat(profiles.selectById(seeker.getId()).getName()).isEqualTo("确认姓名");
        assertThat(vectorTasks.selectCount(new QueryWrapper<VectorSyncTask>().eq("resume_id",r.getId()))).isEqualTo(2);
        assertThat(resumes.selectById(r.getId()).getUpdatedAt()).isNotNull();
    }
    @Test void aiTaskIsPersistentDeduplicatedAuthorizedAndResultValidated() throws Exception {
        Job j=job(); Resume r=resume(); var input=new AiInput(null,r.getId(),1,j.getId(),null);
        String id=data(send("POST","/api/ai/matches",seeker,input).andExpect(status().isAccepted())).path("id").asText();
        send("POST","/api/ai/matches",seeker,input).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        send("GET","/api/ai/tasks/"+id,other,null).andExpect(status().isNotFound());
        when(python.call(eq(HttpMethod.POST),eq("/internal/ai/match"),any())).thenReturn(json.readTree("{\"score\":86,\"reasons\":[\"技能匹配\"],\"gaps\":[]}"));
        ai.processOne();
        send("GET","/api/ai/tasks/"+id,seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCESS")).andExpect(jsonPath("$.data.result.score").value(86)).andExpect(jsonPath("$.data.inputSnapshot").doesNotExist());
        assertThat(matches.selectCount(new QueryWrapper<ResumeJobMatch>().eq("resume_id",r.getId()))).isEqualTo(1);
    }
    @Test void malformedModelResultBecomesFailedAndNotScore() throws Exception {
        Job j=job(); Resume r=resume();
        String id=data(send("POST","/api/ai/matches",seeker,new AiInput(null,r.getId(),1,j.getId(),null))).path("id").asText();
        when(python.call(any(),anyString(),any())).thenReturn(json.readTree("{\"score\":101,\"reasons\":[],\"gaps\":[]}")); ai.processOne();
        assertThat(tasks.selectById(id).getStatus()).isEqualTo("FAILED"); assertThat(tasks.selectById(id).getErrorCode()).isEqualTo(42201);
    }
    @Test void talentSearchRechecksPrivacyAfterPythonReturns() throws Exception {
        Job j=job(); Resume r=resume();
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/talents/search"),any())).thenAnswer(inv->{
            seeker.setDiscoverable(false); profiles.updateById(seeker);
            return json.readTree("{\"matches\":[{\"resumeId\":\""+r.getId()+"\",\"resumeVersion\":1,\"similarity\":0.9}]}");
        });
        send("POST","/api/company/jobs/"+j.getId()+"/talent-search",company,new Talent(10,0.6)).andExpect(status().isOk()).andExpect(jsonPath("$.data.returnedCount").value(0));
    }
    @Test void disabledCompanyImmediatelyDisappearsAndCannotOperate() throws Exception {
        Job j=job();
        send("PATCH","/api/admin/users/"+company.getId()+"/enabled",admin,new Enabled(false,"违规企业")).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(get("/api/jobs/"+j.getId())).andExpect(status().isNotFound());
        send("GET","/api/company/jobs",company,null).andExpect(status().isForbidden());
    }
    @Test void rejectsFakePdfAndParsesRealPdfAsynchronously() throws Exception {
        var fake=new MockMultipartFile("file","fake.pdf","application/pdf","not a pdf".getBytes());
        mvc.perform(multipart("/api/resumes").file(fake).session(session(seeker)).header("X-CSRF-Token","csrf-test").header("X-Profile-Id",seeker.getId())).andExpect(status().isBadRequest());
        byte[] bytes; try(var document=new PDDocument();var out=new ByteArrayOutputStream()) { document.addPage(new PDPage()); document.save(out); bytes=out.toByteArray(); }
        var real=new MockMultipartFile("file","resume.pdf","application/pdf",bytes);
        JsonNode uploaded=data(mvc.perform(multipart("/api/resumes").file(real).session(session(seeker)).header("X-CSRF-Token","csrf-test").header("X-Profile-Id",seeker.getId())).andExpect(status().isAccepted()));
        String id=uploaded.path("resumeId").asText();
        when(python.call(eq(HttpMethod.POST),eq("/internal/resumes/parse"),any())).thenReturn(json.readTree("{\"resumeId\":\""+id+"\",\"resumeVersion\":1,\"extractedText\":\"具有Java开发经验\",\"extractionMethod\":\"TEXT\",\"pageCount\":1,\"parsedName\":\"AI姓名\",\"parsedPhone\":null,\"parsedEducation\":\"BACHELOR\",\"parsedSkills\":[\"Java\"],\"parsedSummary\":null}"));
        resumeService.processOne(); assertThat(resumes.selectById(id).getParseStatus()).isEqualTo("SUCCESS");
        send("GET","/api/resumes/"+id,seeker,null).andExpect(jsonPath("$.data.conflictStatus").value("PENDING_VERIFY")).andExpect(jsonPath("$.data.parsedSkills[0]").value("Java"));
    }
    @Test void optionalResumeSectionsPersistSnapshotAndClearOnReparse() throws Exception {
        Resume r=resume(); r.setParseStatus("PENDING"); r.setConfirmationStatus("UNCONFIRMED"); resumes.updateById(r);
        var result=json.createObjectNode();
        result.put("resumeId",r.getId().toString()); result.put("resumeVersion",1);
        result.put("extractedText","项目：招聘平台，负责接口开发"); result.put("extractionMethod","TEXT"); result.put("pageCount",1);
        result.putNull("parsedName"); result.putNull("parsedPhone"); result.putNull("parsedEducation"); result.putNull("parsedSummary"); result.putArray("parsedSkills");
        result.put("parsedBirthDate","1998-01-01"); result.put("parsedAge",28); result.put("parsedWorkExperienceYears",4);
        result.putArray("parsedProjectExperience").add("招聘平台：负责接口开发"); result.putNull("parsedWorkExperience");
        when(python.call(eq(HttpMethod.POST),eq("/internal/resumes/parse"),any())).thenReturn(result);
        resumeService.processOne();
        Resume stored=resumes.selectById(r.getId()); assertThat(stored.getParseStatus()).isEqualTo("SUCCESS");
        assertThat(stored.getParsedBirthDate()).isEqualTo(java.time.LocalDate.of(1998,1,1)); assertThat(stored.getParsedAge()).isEqualTo(28); assertThat(stored.getParsedWorkExperienceYears()).isEqualTo(4);
        assertThat(resumeService.view(stored).path("parsedProjectExperience").get(0).asText()).contains("招聘平台");
        assertThat(resumeService.view(stored).path("parsedWorkExperience").isNull()).isTrue();
        assertThat(resumeService.optionalSections(stored).path("parsedProjectExperience").isArray()).isTrue();
        send("POST","/api/resumes/"+r.getId()+"/reparse",seeker,new Version(1)).andExpect(status().isAccepted());
        assertThat(resumes.selectById(r.getId()).getParsedProjectExperience()).isNull();
        result.put("resumeVersion",2); result.put("parsedProjectExperience","not an array");
        resumeService.processOne(); assertThat(resumes.selectById(r.getId()).getParseStatus()).isEqualTo("FAILED");
    }
    @Test void confirmationStoresEditedExperienceAndKeepsAiOriginal() throws Exception {
        Resume r=resume(); r.setParsedProjectExperience("[\"AI原文\"]"); resumes.updateById(r);
        var edits=Map.of("parsedProjectExperience",List.of("招聘平台\n负责接口开发"));
        send("POST","/api/resumes/"+r.getId()+"/confirm",seeker,new Confirm(1,"确认姓名","13800138000","MASTER",List.of("Java"),edits))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmedProfile.optionalSections.parsedProjectExperience[0]").value("招聘平台\n负责接口开发"));
        assertThat(resumes.selectById(r.getId()).getParsedProjectExperience()).contains("AI原文");
        send("POST","/api/resumes/"+r.getId()+"/confirm",seeker,new Confirm(2,"确认姓名","13800138000","MASTER",List.of("Java"),Map.of("parsedProjectExperience",List.of())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmedProfile.optionalSections.parsedProjectExperience").isEmpty());
    }
    @Test void companyPageOnlyListsPublishedJobsFromThatCompany() throws Exception {
        Job published=job(); Job hidden=job(); hidden.setStatus("DRAFT"); jobs.updateById(hidden);
        Job foreign=job(); foreign.setCompanyId(other.getId()); jobs.updateById(foreign);
        mvc.perform(get("/api/jobs").param("companyId",company.getId().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].id").value(published.getId().toString()));
        mvc.perform(get("/api/jobs").param("companyId","invalid")).andExpect(status().isBadRequest());
    }
    @Test void profileOptionalFieldsCanBeCleared() throws Exception {
        send("PUT","/api/users/me/profile",seeker,new Personal("张三","BACHELOR","杭州","介绍")).andExpect(status().isOk());
        send("PUT","/api/users/me/profile",seeker,new Personal("张三","BACHELOR",null,null)).andExpect(status().isOk()).andExpect(jsonPath("$.data.city").isEmpty());
    }
    @Test void publicAndOwnedListsUseSalaryIntersectionAndCompanyScope() throws Exception {
        Job j=job();
        mvc.perform(get("/api/jobs").param("salaryMin","14000").param("salaryMax","16000").param("keyword","Java")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/jobs").param("salaryMin","16000")).andExpect(jsonPath("$.data.total").value(0));
        send("GET","/api/company/jobs",other,null).andExpect(jsonPath("$.data.total").value(0));
        send("GET","/api/admin/jobs?companyId="+company.getId(),admin,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }
    @Test void jobFiltersRespectExperienceBoundariesAndMinimumEducation() throws Exception {
        for(int years:new int[]{1,2,3,4,5,6}) { Job j=job();j.setExperienceMinYears(years);j.setEducationRequirement(years<=2?"JUNIOR_COLLEGE":years<=4?"BACHELOR":years==5?"MASTER":"DOCTOR");jobs.updateById(j); }
        for(String range:List.of("1_3","3_5","5_PLUS")) send("GET","/api/jobs?experience="+range,seeker,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        send("GET","/api/jobs?education=BACHELOR",seeker,null).andExpect(jsonPath("$.data.total").value(4));
        send("GET","/api/jobs?education=MASTER&experience=5_PLUS",seeker,null).andExpect(jsonPath("$.data.total").value(2));
        send("GET","/api/jobs?education=OTHER",seeker,null).andExpect(jsonPath("$.data.total").value(0));
        send("GET","/api/jobs?education=invalid",seeker,null).andExpect(status().isBadRequest());
    }
    @Test void companyMetadataFiltersAndCachedDetailsUseCurrentProfile() throws Exception {
        Job j=job(); j.setExperienceMinYears(2); jobs.updateById(j);
        company.setIndustry("软件服务"); company.setCompanySize("100_499"); profiles.updateById(company);
        when(python.call(eq(HttpMethod.POST),eq("/internal/vector/jobs/search"),any())).thenReturn(json.readTree("{\"matches\":[{\"jobId\":\""+j.getId()+"\",\"jobVersion\":1,\"similarity\":0.8}]}"));
        JsonNode cached=data(mvc.perform(get("/api/jobs/"+j.getId())).andExpect(status().isOk()).andExpect(jsonPath("$.data.companySize").value("100_499")));
        mvc.perform(get("/api/jobs").param("industry","软件").param("companySize","100_499").param("experience","1_3"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/jobs").param("companySize","UNDER_20")).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/jobs").param("experience","ENTRY")).andExpect(jsonPath("$.data.total").value(0));
        when(redis.cachedJob(j.getId(),1)).thenReturn(cached.toString());
        company.setCompanySize("500_999"); profiles.updateById(company);
        mvc.perform(get("/api/jobs/"+j.getId())).andExpect(jsonPath("$.data.companySize").value("500_999"));
    }
    @Test void companySizeIsEditableAndValidated() throws Exception {
        send("PUT","/api/company/profile",company,Map.of("companyName","企业资料","industry","软件","companySize","20_99"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.companySize").value("20_99"));
        send("PUT","/api/company/profile",company,Map.of("companyName","企业资料","companySize","INVALID")).andExpect(status().isBadRequest());
        assertThat(profiles.selectById(company.getId()).getCompanySize()).isEqualTo("20_99");
    }
    @Test void vectorWorkerPersistsSuccessAndRecoversInterruptedWork() {
        Resume r=resume(); vectors.enqueue(r,false);
        when(python.call(eq(HttpMethod.PUT),anyString(),any())).thenReturn(json.createObjectNode().put("indexed",true));
        vectors.processOne(); assertThat(resumes.selectById(r.getId()).getIndexStatus()).isEqualTo("READY");
        VectorSyncTask task=vectorTasks.selectOne(new QueryWrapper<VectorSyncTask>().eq("resume_id",r.getId()));
        assertThat(task.getStatus()).isEqualTo("SUCCESS");
        task.setStatus("PROCESSING"); task.setStartedAt(BusinessSupport.now().minusMinutes(11)); vectorTasks.updateById(task);
        vectors.recover(); assertThat(vectorTasks.selectById(task.getId()).getStatus()).isEqualTo("PENDING");
    }
    @Test void interruptedAiIsFailedAndCanBeResubmitted() throws Exception {
        Job j=job(); Resume r=resume(); var input=new AiInput(null,r.getId(),1,j.getId(),null);
        String id=data(send("POST","/api/ai/matches",seeker,input)).path("id").asText();
        AiTask t=tasks.selectById(id); t.setStatus("PROCESSING"); t.setStartedAt(BusinessSupport.now().minusMinutes(11)); tasks.updateById(t);
        ai.recover(); assertThat(tasks.selectById(id).getStatus()).isEqualTo("FAILED");
        send("POST","/api/ai/matches",seeker,input).andExpect(status().isAccepted());
    }
    @Test void lateParseResultCannotOverwriteNewerResumeVersion() throws Exception {
        Resume r=resume(); r.setParseStatus("PENDING"); r.setConfirmationStatus("UNCONFIRMED"); resumes.updateById(r);
        when(python.call(eq(HttpMethod.POST),eq("/internal/resumes/parse"),any())).thenAnswer(inv->{
            Resume update=new Resume(); update.setId(r.getId()); update.setVersion(2); resumes.updateById(update);
            return json.readTree("{\"resumeId\":\""+r.getId()+"\",\"resumeVersion\":1,\"extractedText\":\"旧解析文本\",\"extractionMethod\":\"TEXT\",\"pageCount\":1,\"parsedName\":\"旧姓名\",\"parsedPhone\":null,\"parsedEducation\":null,\"parsedSkills\":[],\"parsedSummary\":null}");
        });
        resumeService.processOne(); Resume latest=resumes.selectById(r.getId());
        assertThat(latest.getVersion()).isEqualTo(2); assertThat(latest.getParsedName()).isNull(); assertThat(latest.getExtractedText()).isEqualTo(r.getExtractedText());
    }
}
