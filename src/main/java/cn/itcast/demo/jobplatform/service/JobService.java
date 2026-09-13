package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.*;
import cn.itcast.demo.jobplatform.dto.RecruitmentRequests.*;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** 职位查询、所属企业操作、管理员审核；公开条件始终查询数据库。 */
@Service
public class JobService {
    private final JobMapper jobs;
    private final ProfileMapper profiles;
    private final ApplicationMapper applications;
    private final BusinessSupport b;
    private final BusinessRedis redis;
    private final AuditService audit;
    private static final String PUBLIC_COMPANIES="select p.id from profile p join account a on a.id=p.account_id where p.role='COMPANY' and p.enabled=1 and a.enabled=1 and p.review_status='APPROVED'";
    public JobService(JobMapper jobs,ProfileMapper profiles,ApplicationMapper applications,BusinessSupport b,BusinessRedis redis,AuditService audit) {
        this.jobs=jobs; this.profiles=profiles; this.applications=applications; this.b=b; this.redis=redis; this.audit=audit;
    }
    public Job require(Long id,boolean lock) {
        Job j=jobs.selectOne(new QueryWrapper<Job>().eq("id",id).last(lock?"FOR UPDATE":""));
        if(j==null) missing(); return j;
    }
    public Job publicJob(Long id) {
        Job j=require(id,false);
        if(!"APPROVED".equals(j.getStatus()) || !b.available(profiles.selectById(j.getCompanyId()))) missing();
        return j;
    }
    public Job own(Long id,Profile p,boolean lock) {
        Job j=require(id,lock); if(!j.getCompanyId().equals(p.getId())) missing(); return j;
    }
    public ObjectNode view(Job j,boolean publicView) {
        ObjectNode out=b.view(j,"deleted"); out.set("skills",b.read(j.getSkills()));
        Profile company=profiles.selectById(j.getCompanyId()); out.put("companyName",company==null?null:company.getCompanyName());
        if(publicView) out.putNull("reviewReason"); return out;
    }
    /** 缓存只存职位展示；先查实时状态，企业名称也实时更新，禁止返回已下架缓存。 */
    public ObjectNode detail(Long id) {
        Job j=publicJob(id); String cached=redis.cachedJob(id,j.getVersion());
        ObjectNode out=cached==null?view(j,true):(ObjectNode)b.read(cached);
        out.put("companyName",profiles.selectById(j.getCompanyId()).getCompanyName());
        if(cached==null) redis.cacheJob(id,j.getVersion(),b.write(out)); return out;
    }
    public PageResult<ObjectNode> list(Map<String,String> q,String scope,HttpServletRequest request) {
        QueryWrapper<Job> w=new QueryWrapper<>();
        if("PUBLIC".equals(scope)) w.eq("status","APPROVED").inSql("company_id",PUBLIC_COMPANIES);
        else {
            Profile p=b.actor(request,"ADMIN".equals(scope)?"ADMIN":"COMPANY");
            if("COMPANY".equals(scope)) w.eq("company_id",p.getId());
            else if(q.containsKey("companyId")&&!q.get("companyId").isBlank()) {
                try { long companyId=Long.parseLong(q.get("companyId")); if(companyId<=0) throw new NumberFormatException(); w.eq("company_id",companyId); }
                catch(NumberFormatException e) { bad("companyId须为有效ID"); }
            }
            if(q.containsKey("status")) w.eq("status",q.get("status"));
        }
        String keyword=trim(q.get("keyword"));
        if(keyword!=null && !keyword.isEmpty()) w.and(n->n.like("title",keyword).or().apply("company_id in (select id from profile where company_name like {0})","%"+keyword+"%"));
        if(q.containsKey("city")&&!q.get("city").isBlank()) w.eq("city",q.get("city"));
        if(q.containsKey("education")&&!q.get("education").isBlank()) w.eq("education_requirement",q.get("education"));
        if(q.containsKey("salaryMin")) w.ge("salary_max",number(q,"salaryMin",0,0,1000000));
        if(q.containsKey("salaryMax")) w.le("salary_min",number(q,"salaryMax",0,0,1000000));
        if(q.containsKey("salaryMin")&&q.containsKey("salaryMax") && Integer.parseInt(q.get("salaryMin"))>Integer.parseInt(q.get("salaryMax"))) bad("薪资下限不能大于上限");
        Page<Job> page=jobs.selectPage(new Page<>(page(q),size(q)),w.orderByDesc("created_at","id"));
        return new PageResult<>(page.getRecords().stream().map(j->view(j,"PUBLIC".equals(scope))).toList(),page.getTotal(),page.getCurrent(),page.getSize());
    }
    public ObjectNode managedDetail(Long id,String scope,HttpServletRequest request) {
        Profile p=b.actor(request,scope); return view("ADMIN".equals(scope)?require(id,false):own(id,p,false),false);
    }
    @Transactional
    public ObjectNode save(Long id,JobInput input,HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY"); if(!b.available(p)) forbidden();
        if(input.salaryMin()>input.salaryMax()) bad("薪资下限不能大于上限");
        if(input.title().trim().length()<2) bad("职位名称至少2个字");
        Job j=id==null?new Job():own(id,p,true);
        if(id!=null && !Set.of("DRAFT","REJECTED").contains(j.getStatus())) state("仅草稿和被拒职位可编辑");
        if(id==null) { j.setCompanyId(p.getId()); j.setVersion(1); j.setDeleted(false); }
        else { redis.evictJob(id,j.getVersion()); j.setVersion(j.getVersion()+1); }
        j.setTitle(input.title().trim()); j.setCity(input.city().trim()); j.setSalaryMin(input.salaryMin()); j.setSalaryMax(input.salaryMax());
        j.setEducationRequirement(input.educationRequirement()); j.setExperienceMinYears(input.experienceMinYears());
        j.setDescription(input.description().trim()); j.setRequirements(input.requirements().trim()); j.setSkills(b.write(input.skills()));
        j.setStatus("DRAFT"); j.setReviewReason(null);
        if(id==null) jobs.insert(j); else jobs.update(j,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Job>().eq("id",id).set("education_requirement",input.educationRequirement()).set("review_reason",null));
        return view(j,false);
    }
    @Transactional
    public ObjectNode transition(Long id,String action,Review review,String reason,HttpServletRequest request) {
        boolean admin=action.startsWith("ADMIN"); Profile p=b.actor(request,admin?"ADMIN":"COMPANY");
        Job j=admin?require(id,true):own(id,p,true); String before=j.getStatus();
        if(action.equals("SUBMIT")) {
            if(!Set.of("DRAFT","REJECTED").contains(before)) state("当前职位不可提交审核"); j.setStatus("PENDING");
        } else if(action.equals("ADMIN_REVIEW")) {
            if(!"PENDING".equals(before)) state("仅待审核职位可审核");
            if("REJECTED".equals(review.decision()) && (review.reason()==null||review.reason().isBlank())) bad("拒绝时必须填写原因");
            if(!b.available(profiles.selectById(j.getCompanyId()))) state("企业尚未通过审核或已禁用");
            j.setStatus(review.decision()); reason=trim(review.reason());
            if("APPROVED".equals(j.getStatus())) j.setPublishedAt(now());
        } else { if(!"APPROVED".equals(before)) state("仅已发布职位可关闭"); j.setStatus("CLOSED"); }
        j.setReviewReason(reason);
        jobs.update(j,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Job>().eq("id",id).set("review_reason",reason));
        redis.evictJob(id,j.getVersion());
        audit.record(p.getId(),"JOB",id,action,reason,b.object("status",before),b.object("status",j.getStatus()));
        return view(j,false);
    }
    @Transactional
    public ObjectNode delete(Long id,HttpServletRequest request) {
        Job j=own(id,b.actor(request,"COMPANY"),true);
        if(!Set.of("DRAFT","REJECTED").contains(j.getStatus()) || applications.selectCount(new QueryWrapper<Application>().eq("job_id",id))>0) state("该职位不能删除");
        jobs.deleteById(id); redis.evictJob(id,j.getVersion()); return b.object();
    }
    /** 企业主页仅返回公开资料，绝不返回手机号、账号或审核原因。 */
    public ObjectNode company(Long id) {
        Profile p=profiles.selectById(id); if(p==null||!"COMPANY".equals(p.getRole())||!b.available(p)) missing();
        return b.object("id",id.toString(),"companyName",p.getCompanyName(),"industry",p.getIndustry(),"city",p.getCity(),"companyDescription",p.getCompanyDescription(),"avatarUrl",p.getAvatarPath()==null?null:"/api/users/"+id+"/avatar");
    }
}
