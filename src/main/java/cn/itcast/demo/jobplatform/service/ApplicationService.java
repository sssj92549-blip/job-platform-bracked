package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.*;
import cn.itcast.demo.jobplatform.dto.RecruitmentRequests.*;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.util.*;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** 投递快照不可变；企业修改展示来源不覆盖求职者档案。 */
@Service
public class ApplicationService {
    private final ApplicationMapper applications;
    private final AiTaskMapper aiTasks;
    private final ResumeMapper resumes;
    private final ResumeJobMatchMapper matches;
    private final ProfileRepository profiles;
    private final JobService jobs;
    private final ResumeService resumeService;
    private final BusinessSupport b;
    private final BusinessRedis redis;
    private final AuditService audit;
    private final FileStorageService files;
    private final TransactionTemplate tx;
    public ApplicationService(AiTaskMapper aiTasks,ApplicationMapper applications,ResumeMapper resumes,ResumeJobMatchMapper matches,ProfileRepository profiles,JobService jobs,ResumeService resumeService,BusinessSupport b,BusinessRedis redis,AuditService audit,FileStorageService files,PlatformTransactionManager tm) {
        this.aiTasks=aiTasks; this.applications=applications; this.resumes=resumes; this.matches=matches; this.profiles=profiles; this.jobs=jobs; this.resumeService=resumeService; this.b=b; this.redis=redis; this.audit=audit; this.files=files; tx=new TransactionTemplate(tm);
    }
    public Application own(Long id,Profile p,boolean lock) {
        Application a=applications.selectOne(new QueryWrapper<Application>().eq("id",id).last(lock?"FOR UPDATE":""));
        if(a==null) missing();
        if("JOB_SEEKER".equals(p.getRole())) { if(!a.getCandidateId().equals(p.getId())) missing(); }
        else if("COMPANY".equals(p.getRole())) { if(!jobs.require(a.getJobId(),false).getCompanyId().equals(p.getId())) missing(); }
        else forbidden(); return a;
    }
    public ObjectNode view(Application a,boolean summary) {
        ObjectNode out=b.view(a,"jobSnapshot"); JsonNode job=b.read(a.getJobSnapshot()),snapshot=b.read(a.getResumeSnapshot());
        out.put("jobTitle",job.path("title").asText()); out.put("companyName",job.path("companyName").asText());
        ResumeJobMatch match=matches.selectOne(new QueryWrapper<ResumeJobMatch>().eq("resume_id",a.getResumeId()).eq("resume_version",a.getResumeVersion()).eq("job_id",a.getJobId()).eq("job_version",a.getJobVersion()));
        out.set("matchScore",b.json.valueToTree(match==null?null:match.getScore()));
        JsonNode confirmed=snapshot.path("confirmedProfile");
        out.set("candidateEducation",confirmed.path("education").isMissingNode()?com.fasterxml.jackson.databind.node.NullNode.instance:confirmed.path("education"));
        out.set("candidateWorkExperienceYears",confirmed.path("workExperienceYears").isMissingNode()?com.fasterxml.jackson.databind.node.NullNode.instance:confirmed.path("workExperienceYears"));
        Integer age=confirmed.hasNonNull("age")?confirmed.path("age").asInt():null;
        try { if(confirmed.hasNonNull("birthDate")) age=java.time.Period.between(java.time.LocalDate.parse(confirmed.path("birthDate").asText()),now().toLocalDate()).getYears(); } catch(java.time.DateTimeException ignored) { }
        out.set("candidateAge",b.json.valueToTree(age));
        if(summary) {
            out.remove("resumeSnapshot"); String name=snapshot.path("confirmedProfile").path("name").asText();
            if("AI".equals(a.getProfileSource()) && snapshot.path("aiProfile").hasNonNull("name")) name=snapshot.path("aiProfile").path("name").asText(); out.put("candidateName",name);
        } else {
            ObjectNode s=(ObjectNode)snapshot.deepCopy(); s.put("downloadUrl","/api/applications/"+a.getId()+"/resume-file"); out.set("resumeSnapshot",s);
        }
        return out;
    }
    public ObjectNode create(Apply input,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER"); redis.limit(p.getId(),"apply",30,60);
        String key="apply:"+p.getId()+":"+input.jobId(),token=redis.lock(key);
        try {
            return tx.execute(s->{
                Job j=jobs.require(input.jobId(),true); Profile company=profiles.selectOne(new QueryWrapper<Profile>().eq("id",j.getCompanyId()).last("FOR UPDATE"));
                if(!"APPROVED".equals(j.getStatus())||!b.available(company)) state("职位当前不可投递");
                Resume r=resumeService.confirmed(input.resumeId(),input.resumeVersion(),p.getId());
                Application a=new Application(); a.setCandidateId(p.getId()); a.setJobId(j.getId()); a.setResumeId(r.getId()); a.setResumeVersion(r.getVersion()); a.setJobVersion(j.getVersion()); a.setStatus("SUBMITTED"); a.setProfileSource("CONFIRMED");
                a.setJobSnapshot(b.write(jobs.view(j,true)));
                a.setResumeSnapshot(b.write(b.object("confirmedProfile",b.read(r.getConfirmedProfile()),"originalProfile",b.read(r.getOriginalProfile()),"aiProfile",b.object("name",r.getParsedName(),"phone",r.getParsedPhone(),"education",r.getParsedEducation(),"skills",b.read(r.getParsedSkills())),"conflictStatus",r.getConflictStatus(),"conflicts",b.read(r.getConflicts()),"extractedText",r.getExtractedText(),"optionalSections",resumeService.optionalSections(r))));
                applications.insert(a); enqueueMatch(a,company.getId()); return view(a,false);
            });
        } catch(DuplicateKeyException e) { throw new BusinessException(HttpStatus.CONFLICT,40903,"已投递该职位，撤回后也不能重复投递"); }
        finally { redis.unlock(key,token); }
    }
    /** 与投递同事务保存一次自动评分任务；模型调用由后台执行，不阻塞投递。 */
    private void enqueueMatch(Application a,Long companyId) {
        if(matches.selectCount(new QueryWrapper<ResumeJobMatch>().eq("resume_id",a.getResumeId()).eq("resume_version",a.getResumeVersion()).eq("job_id",a.getJobId()).eq("job_version",a.getJobVersion()))>0) return;
        JsonNode snapshot=b.read(a.getResumeSnapshot()), job=b.read(a.getJobSnapshot());
        String text="已确认资料："+snapshot.path("confirmedProfile")+"\n简历原文：\n"+snapshot.path("extractedText").asText();
        if(text.length()>60000) text=text.substring(0,60000);
        AiTask task=new AiTask(); task.setCreatorId(companyId); task.setType("MATCH"); task.setStatus("PENDING");
        task.setApplicationId(a.getId()); task.setResumeId(a.getResumeId()); task.setResumeVersion(a.getResumeVersion()); task.setJobId(a.getJobId()); task.setJobVersion(a.getJobVersion());
        task.setRequestKey("auto-match:"+a.getId());
        task.setInputSnapshot(b.write(b.object("resumeText",text,"job",b.object("title",job.path("title"),"description",job.path("description"),"requirements",job.path("requirements"),"skills",job.path("skills")))));
        aiTasks.insert(task);
    }
    public PageResult<ObjectNode> list(Map<String,String> q,boolean company,HttpServletRequest request) {
        Profile p=b.actor(request,company?"COMPANY":"JOB_SEEKER"); QueryWrapper<Application> w=new QueryWrapper<>();
        if(company) w.apply("job_id in (select id from job where company_id={0})",p.getId()); else w.eq("candidate_id",p.getId());
        if(q.containsKey("jobId")&&!q.get("jobId").isBlank()) w.eq("job_id",q.get("jobId"));
        if(q.containsKey("status")&&!q.get("status").isBlank()) w.eq("status",q.get("status"));
        if(company) applyCandidateFilters(w,q);
        Page<Application> page=applications.selectPage(new Page<>(page(q),size(q)),w.orderByDesc("created_at","id"));
        return new PageResult<>(page.getRecords().stream().map(a->view(a,true)).toList(),page.getTotal(),page.getCurrent(),page.getSize());
    }
    /** 使用投递时确认资料筛选，避免候选人之后修改简历导致历史记录变化。 */
    private void applyCandidateFilters(QueryWrapper<Application> w,Map<String,String> q) {
        String education=q.get("education");
        if(education!=null&&!education.isBlank()) {
            if(!Set.of("HIGH_SCHOOL","JUNIOR_COLLEGE","BACHELOR","MASTER","DOCTOR","OTHER").contains(education)) bad("学历筛选无效");
            w.apply("JSON_UNQUOTE(JSON_EXTRACT(resume_snapshot,'$.confirmedProfile.education'))={0}",education);
        }
        String years="CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(resume_snapshot,'$.confirmedProfile.workExperienceYears')),'null') AS UNSIGNED)";
        String experience=q.get("experience");
        if(experience!=null&&!experience.isBlank()) switch(experience) {
            case "0" -> w.apply(years+"=0");
            case "1_3" -> w.apply(years+" BETWEEN 1 AND 3");
            case "3_5" -> w.apply(years+">3 AND "+years+"<=5");
            case "5_10" -> w.apply(years+">5 AND "+years+"<=10");
            case "10_PLUS" -> w.apply(years+">10");
            default -> bad("工作年限筛选无效");
        }
        Integer min=optionalAge(q,"ageMin"),max=optionalAge(q,"ageMax");
        if(min!=null&&max!=null&&min>max) bad("年龄下限不能大于上限");
        String birthDate="NULLIF(JSON_UNQUOTE(JSON_EXTRACT(resume_snapshot,'$.confirmedProfile.birthDate')),'null')";
        java.time.LocalDate today=now().toLocalDate();
        if(min!=null) w.apply("("+birthDate+"<={0} OR ("+birthDate+" IS NULL AND CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(resume_snapshot,'$.confirmedProfile.age')),'null') AS UNSIGNED)>={1}))",today.minusYears(min).toString(),min);
        if(max!=null) w.apply("("+birthDate+">{0} OR ("+birthDate+" IS NULL AND CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(resume_snapshot,'$.confirmedProfile.age')),'null') AS UNSIGNED)<={1}))",today.minusYears(max+1L).toString(),max);
    }
    private Integer optionalAge(Map<String,String> q,String key) {
        String value=q.get(key); if(value==null||value.isBlank()) return null;
        return number(q,key,0,0,120);
    }
    public ObjectNode detail(Long id,HttpServletRequest request) { return view(own(id,b.actor(request,"JOB_SEEKER","COMPANY"),false),false); }
    @Transactional
    public ObjectNode change(Long id,String status,String source,HttpServletRequest request) {
        Profile p=b.actor(request,"WITHDRAWN".equals(status)?"JOB_SEEKER":"COMPANY"); Application a=own(id,p,true);
        String before=a.getStatus();
        if(source!=null) {
            if("WITHDRAWN".equals(before)) state("已撤回投递不能更改展示来源");
            String previous=a.getProfileSource(); a.setProfileSource(source);
            audit.record(p.getId(),"APPLICATION",id,"ADOPT_"+source,null,b.object("profileSource",previous),b.object("profileSource",source));
        } else if(!before.equals(status)) {
            Set<String> allowed=switch(before) {
                case "SUBMITTED" -> Set.of("VIEWED","SHORTLISTED","REJECTED","WITHDRAWN");
                case "VIEWED" -> Set.of("SHORTLISTED","REJECTED","WITHDRAWN");
                case "SHORTLISTED" -> Set.of("REJECTED","WITHDRAWN"); default -> Set.of();
            };
            if(!allowed.contains(status)) state("当前投递状态不允许此操作"); a.setStatus(status);
        }
        applications.updateById(a); return view(a,false);
    }
    public ResponseEntity<Resource> download(Long id,HttpServletRequest request) {
        Application a=own(id,b.actor(request,"JOB_SEEKER","COMPANY"),false);
        // 附件可能属于已被替换或逻辑删除的简历，使用专用只读Mapper获取历史文件。
        Resume r=resumes.historical(a.getResumeId()); if(r==null) missing(); return files.download(r.getFilePath(),r.getFileName(),true);
    }
}
