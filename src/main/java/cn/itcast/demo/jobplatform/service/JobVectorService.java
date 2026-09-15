package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.entity.Job;
import cn.itcast.demo.jobplatform.mapper.JobMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** 职位向量持久化队列；每个职位串行同步，检索始终以实时业务白名单为准。 */
@Service
public class JobVectorService {
    private final JdbcTemplate db;
    private final JobMapper jobs;
    private final ProfileRepository profiles;
    private final PythonAiClient python;
    private final BusinessSupport b;
    private final TransactionTemplate tx;
    public JobVectorService(JdbcTemplate db,JobMapper jobs,ProfileRepository profiles,PythonAiClient python,BusinessSupport b,PlatformTransactionManager tm) {
        this.db=db; this.jobs=jobs; this.profiles=profiles; this.python=python; this.b=b; tx=new TransactionTemplate(tm);
    }
    /** 必须在职位写入事务中调用，提交后后台才可领取任务。 */
    public void enqueue(Job j,boolean delete) {
        String text=j.getTitle()+"\n"+j.getDescription()+"\n"+j.getRequirements()+"\n"+j.getSkills();
        db.update("INSERT INTO job_vector_task(job_id,job_version,operation,status,payload,attempts,next_attempt_at) VALUES(?,?,?,'PENDING',?,0,?)",
            j.getId(),j.getVersion(),delete?"DELETE":"UPSERT",delete?null:b.write(b.object("text",text)),now());
    }
    public Map<String,Object> latest(Long id) {
        var rows=db.queryForList("SELECT * FROM job_vector_task WHERE job_id=? ORDER BY id DESC LIMIT 1",id);
        return rows.isEmpty()?Map.of():rows.get(0);
    }
    /** 有限自动重试后允许用户手动恢复，不更改职位内容版本。 */
    public void retry(Job j) {
        var task=latest(j.getId());
        if(!"FAILED".equals(task.get("status"))) state("仅失败的职位索引可重试");
        enqueue(j,!"APPROVED".equals(j.getStatus()));
    }
    /** 自动补建历史职位；也补偿企业禁用、恢复及旧版本索引清理。 */
    public void reconcile() {
        for(Job candidate:jobs.selectList(new QueryWrapper<Job>())) tx.executeWithoutResult(s->{
            Job j=jobs.selectOne(new QueryWrapper<Job>().eq("id",candidate.getId()).last("FOR UPDATE"));
            if(j==null) return;
            boolean visible="APPROVED".equals(j.getStatus())&&b.available(profiles.selectById(j.getCompanyId()));
            var previous=latest(j.getId());
            if(previous.isEmpty()) { if(visible) enqueue(j,false); return; }
            int version=((Number)previous.get("job_version")).intValue();
            if(version!=j.getVersion()) {
                if("UPSERT".equals(previous.get("operation"))) {
                    Job old=new Job(); old.setId(j.getId()); old.setVersion(version); enqueue(old,true);
                }
                if(visible) enqueue(j,false);
            } else if(visible!="UPSERT".equals(previous.get("operation"))) enqueue(j,!visible);
        });
    }
    public void processOne() {
        db.update("UPDATE job_vector_task SET status='PENDING',next_attempt_at=? WHERE status='PROCESSING' AND started_at<?",now(),now().minusMinutes(10));
        var rows=db.queryForList("SELECT t.* FROM job_vector_task t WHERE status='PENDING' AND next_attempt_at<=? AND NOT EXISTS (SELECT 1 FROM job_vector_task old WHERE old.job_id=t.job_id AND old.id<t.id AND old.status IN ('PENDING','PROCESSING')) ORDER BY id LIMIT 1",now());
        if(rows.isEmpty()) return;
        var t=rows.get(0); Object id=t.get("id");
        if(db.update("UPDATE job_vector_task SET status='PROCESSING',started_at=?,attempts=attempts+1 WHERE id=? AND status='PENDING'",now(),id)!=1) return;
        try {
            Job j=jobs.selectById((java.io.Serializable)t.get("job_id"));
            boolean delete="DELETE".equals(t.get("operation")) || j==null || j.getVersion()!=((Number)t.get("job_version")).intValue() || !"APPROVED".equals(j.getStatus()) || !b.available(profiles.selectById(j.getCompanyId()));
            JsonNode response=python.call(delete?HttpMethod.DELETE:HttpMethod.PUT,"/internal/vector/jobs/"+t.get("job_id")+"/versions/"+t.get("job_version"),delete?null:b.read(t.get("payload").toString()));
            if(response==null||!response.path(delete?"deleted":"indexed").asBoolean(false)) PythonAiClient.invalid();
            db.update("UPDATE job_vector_task SET status='SUCCESS',completed_at=?,error_message=NULL WHERE id=? AND status='PROCESSING'",now(),id);
        } catch(Exception e) {
            int attempts=((Number)t.get("attempts")).intValue()+1;
            db.update("UPDATE job_vector_task SET status=?,next_attempt_at=?,error_message=? WHERE id=? AND status='PROCESSING'",attempts<3?"PENDING":"FAILED",now().plusSeconds(30L*attempts),"职位向量同步失败，请检查AI服务后重试",id);
        }
    }
    /** 返回全量白名单内命中，交由Java复核、融合排序后分页，避免先分页导致漏召回。 */
    public Map<Long,Double> search(String keyword,List<Job> allowed) { return search(keyword,allowed,0.55); }
    public Map<Long,Double> search(String keyword,List<Job> allowed,double minimum) { return search(keyword,allowed,minimum,null,false); }
    public Map<Long,Double> recommend(String question,String resumeText,List<Job> allowed) { return search(question,allowed,0.65,resumeText,true); }
    private Map<Long,Double> search(String keyword,List<Job> allowed,double minimum,String resumeText,boolean required) {
        Map<Long,Double> found=new HashMap<>(); if(allowed.isEmpty()) return found;
        var refs=b.json.createArrayNode(); Map<Long,Integer> versions=new HashMap<>();
        for(Job j:allowed) { refs.add(b.object("jobId",j.getId().toString(),"jobVersion",j.getVersion())); versions.put(j.getId(),j.getVersion()); }
        try {
            var payload=b.object("queryText",keyword,"eligibleJobs",refs,"minSimilarity",minimum);
            if(resumeText!=null) payload.put("resumeText",resumeText);
            JsonNode result=python.call(HttpMethod.POST,"/internal/vector/jobs/search",payload);
            if(result==null||!result.path("matches").isArray()) { if(required) PythonAiClient.invalid(); return found; }
            for(JsonNode item:result.path("matches")) {
                Long id=Long.valueOf(item.path("jobId").asText()); double score=item.path("similarity").asDouble(-1);
                if(Objects.equals(versions.get(id),item.path("jobVersion").asInt())&&Double.isFinite(score)&&score>=minimum&&score<=1) found.put(id,score);
            }
        } catch(Exception e) { if(required) throw new cn.itcast.demo.jobplatform.common.BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,50301,"职位推荐服务暂不可用，请稍后重试"); org.slf4j.LoggerFactory.getLogger(getClass()).warn("Job vector search unavailable; using keyword search"); }
        return found;
    }
}
