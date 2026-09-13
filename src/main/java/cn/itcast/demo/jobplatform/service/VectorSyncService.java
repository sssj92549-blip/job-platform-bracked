package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** 向量同步采用MySQL任务表；重试幂等，检索白名单独立保证隐私。 */
@Service
public class VectorSyncService {
    private final VectorSyncTaskMapper tasks;
    private final ResumeMapper resumes;
    private final ProfileMapper profiles;
    private final PythonAiClient python;
    private final BusinessSupport b;
    private final TransactionTemplate tx;
    public VectorSyncService(VectorSyncTaskMapper tasks,ResumeMapper resumes,ProfileMapper profiles,PythonAiClient python,BusinessSupport b,PlatformTransactionManager tm) {
        this.tasks=tasks; this.resumes=resumes; this.profiles=profiles; this.python=python; this.b=b; tx=new TransactionTemplate(tm);
    }
    /** 由调用方事务写入，提交前后台不能领取该任务。 */
    public void enqueue(Resume r,boolean delete) {
        VectorSyncTask t=new VectorSyncTask(); t.setResumeId(r.getId()); t.setResumeVersion(r.getVersion());
        t.setOperation(delete?"DELETE":"UPSERT"); t.setStatus("PENDING"); t.setAttempts(0); t.setNextAttemptAt(now());
        if(!delete) {
            Profile p=profiles.selectById(r.getCandidateId());
            String text=r.getExtractedText();
            if("CONFIRMED".equals(r.getConfirmationStatus())) {
                var confirmed=b.read(r.getConfirmedProfile());
                text="已确认学历："+confirmed.path("education").asText()+"；技能："+confirmed.path("skills")+"\n"+text;
                if(text.length()>60000) bad("简历和确认资料超过索引长度限制，请精简简历");
            }
            t.setPayload(b.write(b.object("candidateId",p.getId().toString(),"text",text,"metadata",b.object("confirmed","CONFIRMED".equals(r.getConfirmationStatus()),"discoverable",Boolean.TRUE.equals(p.getDiscoverable())&&b.available(p)))));
        }
        tasks.insert(t);
        r.setIndexStatus(delete?"DELETING":"PENDING"); r.setIndexError(null);
        resumes.update(r,new UpdateWrapper<Resume>().eq("id",r.getId()).eq("version",r.getVersion()).set("index_error",null));
    }
    /** 同一简历只领取最早未结束任务，防止删除与迟到写入互相覆盖。 */
    public void processOne() {
        VectorSyncTask t=tasks.selectOne(new QueryWrapper<VectorSyncTask>().eq("status","PENDING").le("next_attempt_at",now())
            .apply("not exists (select 1 from vector_sync_task older where older.resume_id=vector_sync_task.resume_id and older.id<vector_sync_task.id and older.status in ('PENDING','PROCESSING'))")
            .orderByAsc("id").last("LIMIT 1"));
        if(t==null) return;
        t.setStatus("PROCESSING"); t.setStartedAt(now()); t.setAttempts(t.getAttempts()+1);
        if(tasks.update(t,new UpdateWrapper<VectorSyncTask>().eq("id",t.getId()).eq("status","PENDING"))!=1) return;
        try {
            Resume current=resumes.selectById(t.getResumeId()); Profile p=current==null?null:profiles.selectById(current.getCandidateId());
            boolean valid=current!=null && Boolean.TRUE.equals(current.getIsCurrent()) && current.getVersion().equals(t.getResumeVersion()) && "SUCCESS".equals(current.getParseStatus()) && b.available(p);
            boolean delete="DELETE".equals(t.getOperation()) || !valid;
            python.call(delete?HttpMethod.DELETE:HttpMethod.PUT,"/internal/vector/resumes/"+t.getResumeId()+"/versions/"+t.getResumeVersion(),delete?null:b.read(t.getPayload()));
            tx.executeWithoutResult(s->{
                t.setStatus("SUCCESS"); t.setCompletedAt(now()); tasks.updateById(t);
                Resume update=new Resume(); update.setIndexStatus(delete?"DELETED":"READY");
                resumes.update(update,new UpdateWrapper<Resume>().eq("id",t.getResumeId()).eq("version",t.getResumeVersion())
                    .set("index_error",null).apply("not exists (select 1 from vector_sync_task newer where newer.resume_id={0} and newer.id>{1})",t.getResumeId(),t.getId()));
            });
        } catch(Exception e) {
            tx.executeWithoutResult(s->{
                String message=e instanceof cn.itcast.demo.jobplatform.common.BusinessException?e.getMessage():"向量同步失败，请稍后重试";
                t.setStatus(t.getAttempts()<3?"PENDING":"FAILED"); t.setErrorMessage(message); t.setNextAttemptAt(now().plusSeconds(30L*t.getAttempts())); tasks.updateById(t);
                if("FAILED".equals(t.getStatus())) {
                    Resume update=new Resume(); update.setIndexStatus("FAILED"); update.setIndexError(message);
                    resumes.update(update,new UpdateWrapper<Resume>().eq("id",t.getResumeId()).eq("version",t.getResumeVersion()));
                }
            });
        }
    }
    public void recover() {
        VectorSyncTask t=new VectorSyncTask(); t.setStatus("PENDING"); t.setNextAttemptAt(now());
        tasks.update(t,new UpdateWrapper<VectorSyncTask>().eq("status","PROCESSING").lt("started_at",now().minusMinutes(10)));
    }
}
