package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.*;
import cn.itcast.demo.jobplatform.dto.RecruitmentRequests.AiInput;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DuplicateKeyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** AI异步任务持久化、输入快照和结果验证；Redis负责限频，MySQL负责幂等与恢复。 */
@Service
public class AiTaskService {
    private final AiTaskMapper tasks;
    private final JobRecommendationService recommendations;
    private final ResumeJobMatchMapper matches;
    private final ResumeService resumes;
    private final ApplicationService applications;
    private final JobService jobs;
    private final BusinessSupport b;
    private final BusinessRedis redis;
    private final PythonAiClient python;
    private final TransactionTemplate tx;
    public AiTaskService(JobRecommendationService recommendations,AiTaskMapper tasks,ResumeJobMatchMapper matches,ResumeService resumes,ApplicationService applications,JobService jobs,BusinessSupport b,BusinessRedis redis,PythonAiClient python,PlatformTransactionManager tm) {
        this.recommendations=recommendations; this.tasks=tasks; this.matches=matches; this.resumes=resumes; this.applications=applications; this.jobs=jobs; this.b=b; this.redis=redis; this.python=python; tx=new TransactionTemplate(tm);
    }
    public record Submission(boolean reused,ObjectNode task) {}
    public ObjectNode view(AiTask t) {
        ObjectNode out=b.view(t,"creatorId","question","requestKey","inputSnapshot","startedAt","updatedAt"); out.set("result",b.read(t.getResult()));
        if("ASSISTANT".equals(t.getType())) {
            recommendations.refreshSources(out.path("result"));
            out.put("question",t.getQuestion());
            out.set("previousTaskId",b.read(t.getInputSnapshot()).path("previousTaskId"));
        }
        return out;
    }
    public ObjectNode get(Long id,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER","COMPANY"); AiTask t=tasks.selectById(id);
        if(t==null||!t.getCreatorId().equals(p.getId())) missing(); return view(t);
    }
    private AiTask ownAssistant(Long id,Long owner) {
        AiTask task=tasks.selectById(id);
        if(task==null||!task.getCreatorId().equals(owner)||!"ASSISTANT".equals(task.getType())) missing();
        return task;
    }
    private Long previous(AiTask task) {
        JsonNode id=b.read(task.getInputSnapshot()).path("previousTaskId");
        return id.isMissingNode()||id.isNull()?null:Long.valueOf(id.asText());
    }
    /** 分页读取数据库中的会话链，客户端不能伪造助手历史。 */
    public ObjectNode conversation(Long id,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER");
        List<ObjectNode> records=new ArrayList<>(); Set<Long> seen=new HashSet<>();
        while(id!=null&&records.size()<50) {
            if(!seen.add(id)) state("会话记录异常");
            AiTask task=ownAssistant(id,p.getId()); records.add(view(task)); id=previous(task);
        }
        Collections.reverse(records);
        return b.object("records",records,"nextTaskId",id==null?null:id.toString());
    }
    private ArrayNode history(Long id,AiTask current) {
        List<ObjectNode> turns=new ArrayList<>(); int length=0; Set<Long> seen=new HashSet<>();
        while(id!=null&&turns.size()<6) {
            if(!seen.add(id)) state("会话记录异常");
            AiTask task=ownAssistant(id,current.getCreatorId());
            if(!Objects.equals(task.getResumeId(),current.getResumeId())||!Objects.equals(task.getResumeVersion(),current.getResumeVersion())) state("简历已更新，请开始新对话");
            if(!"SUCCESS".equals(task.getStatus())) state("请等待上一条回答完成，失败后请重试");
            JsonNode result=b.read(task.getResult());
            ObjectNode turn=b.object("question",task.getQuestion(),"answer",result.path("answer"),"sources",result.path("sources"));
            length+=turn.toString().length(); if(length>30000) break;
            turns.add(turn); id=previous(task);
        }
        Collections.reverse(turns); ArrayNode out=b.json.createArrayNode(); turns.forEach(out::add); return out;
    }
    public Submission create(String type,AiInput input,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER","COMPANY");
        redis.limit(p.getId(),"ai-minute",6,60); redis.limit(p.getId(),"ai-hour",60,3600);
        String key="ai:"+p.getId(),token=redis.lock(key);
        try { return tx.execute(s->build(p,type,input)); }
        finally { redis.unlock(key,token); }
    }
    private Submission build(Profile p,String type,AiInput input) {
        if(!"ASSISTANT".equals(type)&&input.previousTaskId()!=null) bad("仅岗位推荐对话接受previousTaskId");
        AiTask task=new AiTask(); task.setCreatorId(p.getId()); task.setType(type); task.setStatus("PENDING");
        JsonNode confirmed; String resumeText; String summary=null; ObjectNode job=null;
        if("COMPANY".equals(p.getRole())) {
            if("ASSISTANT".equals(type)||input.applicationId()==null||input.resumeId()!=null||input.resumeVersion()!=null||input.jobId()!=null||input.question()!=null) bad("企业AI接口只接受applicationId");
            Application a=applications.own(input.applicationId(),p,true); if("WITHDRAWN".equals(a.getStatus())) state("不能分析已撤回投递");
            JsonNode snapshot=b.read(a.getResumeSnapshot()); confirmed=snapshot.path("confirmedProfile"); resumeText=snapshot.path("extractedText").asText(); job=jobInput(b.read(a.getJobSnapshot()));
            task.setApplicationId(a.getId()); task.setResumeId(a.getResumeId()); task.setResumeVersion(a.getResumeVersion()); task.setJobId(a.getJobId()); task.setJobVersion(a.getJobVersion());
        } else {
            if(input.applicationId()!=null||input.resumeId()==null||input.resumeVersion()==null) bad("请提供当前简历ID和版本");
            Resume r=resumes.confirmed(input.resumeId(),input.resumeVersion(),p.getId()); confirmed=b.read(r.getConfirmedProfile()); resumeText=r.getExtractedText(); summary=r.getParsedSummary();
            task.setResumeId(r.getId()); task.setResumeVersion(r.getVersion());
            if("ASSISTANT".equals(type)) {
                if(input.jobId()!=null||input.question()==null||input.question().isBlank()) bad("求职助手需要问题且不接受职位ID"); task.setQuestion(input.question().trim());
            } else {
                if(input.jobId()==null||input.question()!=null) bad("请选择职位，不接受question字段");
                Job j=jobs.publicJob(input.jobId()); task.setJobId(j.getId()); task.setJobVersion(j.getVersion()); job=jobInput(jobs.view(j,true));
            }
        }
        ObjectNode payload;
        if("ASSISTANT".equals(type)) {
            payload=b.object("question",task.getQuestion(),"resumeText",recommendations.context(confirmed),"history",history(input.previousTaskId(),task));
            if(input.previousTaskId()!=null) payload.put("previousTaskId",input.previousTaskId().toString());
        }
        else {
            String text="已确认资料："+confirmed+"\n简历原文：\n"+resumeText;
            if(text.length()>60000) bad("简历和确认资料合计超过60000字，请精简后重试");
            payload=b.object("resumeText",text,"job",job); if("INTERVIEW".equals(type)) payload.put("count",10);
        }
        task.setInputSnapshot(b.write(payload));
        task.setRequestKey(hash(p.getId()+":"+type+":"+task.getResumeId()+":"+task.getResumeVersion()+":"+task.getJobId()+":"+task.getJobVersion()+":"+task.getApplicationId()+":"+task.getQuestion()+":"+input.previousTaskId()));
        AiTask existing=tasks.selectOne(new QueryWrapper<AiTask>().eq("request_key",task.getRequestKey()).in("status","PENDING","PROCESSING").last("LIMIT 1"));
        if(existing!=null) return new Submission(true,view(existing));
        if(tasks.selectCount(new QueryWrapper<AiTask>().eq("creator_id",p.getId()).in("status","PENDING","PROCESSING"))>=3) state("最多同时执行3个AI任务，请等待完成");
        tasks.insert(task); return new Submission(false,view(task));
    }
    private ObjectNode jobInput(JsonNode job) { return b.object("title",job.path("title"),"description",job.path("description"),"requirements",job.path("requirements"),"skills",job.path("skills")); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    /** 领取必须比较旧状态；网络调用期间不占用事务连接。 */
    public void processOne() {
        AiTask t=tasks.selectOne(new QueryWrapper<AiTask>().eq("status","PENDING").orderByAsc("id").last("LIMIT 1")); if(t==null) return;
        t.setStatus("PROCESSING"); t.setStartedAt(now());
        if(tasks.update(t,new UpdateWrapper<AiTask>().eq("id",t.getId()).eq("status","PENDING"))!=1) return;
        try {
            String path=switch(t.getType()) { case "MATCH"->"match"; case "INTERVIEW"->"interview-questions"; default->"assistant"; };
            JsonNode input=b.read(t.getInputSnapshot());
            JsonNode result="ASSISTANT".equals(t.getType())
                ? recommendations.answer(input.path("question").asText(),input.hasNonNull("resumeText")?input.path("resumeText").asText():recommendations.context(input.path("profile")),input.path("history"))
                : python.call(HttpMethod.POST,"/internal/ai/"+path,input);
            validate(t.getType(),result);
            tx.executeWithoutResult(s->{
                AiTask fresh=tasks.selectOne(new QueryWrapper<AiTask>().eq("id",t.getId()).eq("status","PROCESSING").last("FOR UPDATE")); if(fresh==null) return;
                fresh.setStatus("SUCCESS"); fresh.setResult(result.toString()); fresh.setCompletedAt(now()); tasks.updateById(fresh);
                if("MATCH".equals(t.getType())) {
                    ResumeJobMatch match=new ResumeJobMatch(); match.setResumeId(t.getResumeId()); match.setResumeVersion(t.getResumeVersion()); match.setJobId(t.getJobId()); match.setJobVersion(t.getJobVersion());
                    match.setScore(result.path("score").asInt()); match.setReasons(result.path("reasons").toString()); match.setGaps(result.path("gaps").toString());
                    try { matches.insert(match); } catch(DuplicateKeyException ignored) { /* 已有同版本评分，保持首次成功结果。 */ }
                }
            });
        } catch(Exception e) {
            AiTask failed=new AiTask(); failed.setStatus("FAILED"); failed.setCompletedAt(now()); failed.setErrorCode(e instanceof BusinessException be?be.getCode():50201); failed.setErrorMessage(e instanceof BusinessException?e.getMessage():"AI任务失败，请重试");
            tasks.update(failed,new UpdateWrapper<AiTask>().eq("id",t.getId()).eq("status","PROCESSING"));
        }
    }
    /** Java再次验证模型结果，不将Python返回值直接当成可信业务数据。 */
    static void validate(String type,JsonNode n) {
        if(!n.isObject()) PythonAiClient.invalid();
        if("MATCH".equals(type)) {
            if(!n.path("score").isIntegralNumber()||!n.path("score").canConvertToInt()||n.path("score").asInt()<0||n.path("score").asInt()>100) PythonAiClient.invalid();
            strings(n.path("reasons"),1,20,1000); strings(n.path("gaps"),0,20,1000);
        } else if("INTERVIEW".equals(type)) {
            JsonNode qs=n.path("questions"); if(!qs.isArray()||qs.size()!=10) PythonAiClient.invalid();
            for(int i=0;i<10;i++) { JsonNode q=qs.get(i); if(!q.path("number").isIntegralNumber()||q.path("number").asInt()!=i+1) PythonAiClient.invalid(); text(q.path("question"),2000); text(q.path("direction"),200); strings(q.path("assessmentPoints"),1,20,100); }
        } else text(n.path("answer"),8000);
    }
    private static void strings(JsonNode n,int min,int max,int length) { if(!n.isArray()||n.size()<min||n.size()>max) PythonAiClient.invalid(); for(JsonNode v:n) text(v,length); }
    private static void text(JsonNode n,int max) { if(!n.isTextual()||n.asText().isBlank()||n.asText().length()>max) PythonAiClient.invalid(); }
    public void recover() {
        AiTask failed=new AiTask(); failed.setStatus("FAILED"); failed.setCompletedAt(now()); failed.setErrorCode(50401); failed.setErrorMessage("任务中断或超时，请重新提交");
        tasks.update(failed,new UpdateWrapper<AiTask>().eq("status","PROCESSING").lt("started_at",now().minusMinutes(10)));
    }
}
