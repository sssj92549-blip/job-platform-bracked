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
    private final ProfileRepository profiles;
    private final ApplicationMapper applications;
    private final BusinessSupport b;
    private final BusinessRedis redis;
    private final AuditService audit;
    private static final String PUBLIC_COMPANIES="select p.id from profile_details p join account a on a.id=p.account_id where p.role='COMPANY' and p.enabled=1 and a.enabled=1 and p.review_status='APPROVED'";
    public JobService(JobMapper jobs,ProfileRepository profiles,ApplicationMapper applications,BusinessSupport b,BusinessRedis redis,AuditService audit) {
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
        companyFields(out,profiles.selectById(j.getCompanyId()));
        if(publicView) out.putNull("reviewReason"); return out;
    }
    /** 缓存只存职位展示；先查实时状态，企业名称也实时更新，禁止返回已下架缓存。 */
    public ObjectNode detail(Long id) {
        Job j=publicJob(id); String cached=redis.cachedJob(id,j.getVersion());
        ObjectNode out=cached==null?view(j,true):(ObjectNode)b.read(cached);
        companyFields(out,profiles.selectById(j.getCompanyId()));
        if(cached==null) redis.cacheJob(id,j.getVersion(),b.write(out)); return out;
    }
    public PageResult<ObjectNode> list(Map<String,String> q,String scope,HttpServletRequest request) {
        QueryWrapper<Job> w=new QueryWrapper<>();
        if("PUBLIC".equals(scope)) {
            w.eq("status","APPROVED").inSql("company_id",PUBLIC_COMPANIES);
            if(q.containsKey("companyId")&&!q.get("companyId").isBlank()) {
                try { long companyId=Long.parseLong(q.get("companyId")); if(companyId<=0) throw new NumberFormatException(); w.eq("company_id",companyId); }
                catch(NumberFormatException e) { bad("companyId须为有效ID"); }
            }
        }
        else {
            Profile p=b.actor(request,"ADMIN".equals(scope)?"ADMIN":"COMPANY");
            if("COMPANY".equals(scope)) w.eq("company_id",p.getId());
            else if(q.containsKey("companyId")&&!q.get("companyId").isBlank()) {
                try { long companyId=Long.parseLong(q.get("companyId")); if(companyId<=0) throw new NumberFormatException(); w.eq("company_id",companyId); }
                catch(NumberFormatException e) { bad("companyId须为有效ID"); }
            }
            if(q.containsKey("status")&&!q.get("status").isBlank()) w.eq("status",q.get("status"));
        }
        String keyword=trim(q.get("keyword"));
        if(keyword!=null && !keyword.isEmpty()) w.and(n->n.like("title",keyword).or().apply("company_id in (select id from profile_details where company_name like {0})","%"+keyword+"%"));
        if(q.containsKey("city")&&!q.get("city").isBlank()) w.eq("city",q.get("city"));
        if(q.containsKey("education")&&!q.get("education").isBlank()) w.eq("education_requirement",q.get("education"));
        if(q.containsKey("industry")&&!q.get("industry").isBlank()) {
            if(q.get("industry").length()>100) bad("行业筛选最多100字");
            w.apply("company_id in (select id from profile_details where industry like {0})","%"+q.get("industry").trim()+"%");
        }
        if(q.containsKey("companySize")&&!q.get("companySize").isBlank()) {
            if(!Set.of("UNDER_20","20_99","100_499","500_999","1000_9999","10000_PLUS").contains(q.get("companySize"))) bad("公司规模无效");
            w.apply("company_id in (select id from profile_details where company_size={0})",q.get("companySize"));
        }
        if(q.containsKey("experience")&&!q.get("experience").isBlank()) {
            switch(q.get("experience")) {
                case "ENTRY" -> w.eq("experience_min_years",0);
                case "1_3" -> w.between("experience_min_years",1,3);
                case "3_5" -> w.gt("experience_min_years",3).le("experience_min_years",5);
                case "5_PLUS" -> w.gt("experience_min_years",5);
                default -> bad("工作经验筛选无效");
            }
        }
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
            if(!Set.of("DRAFT","REJECTED","PENDING").contains(before)) state("当前职位不可发布");
            Profile company=profiles.selectOne(new QueryWrapper<Profile>().eq("id",p.getId()).last("FOR UPDATE"));
            if(!b.available(company)) state("企业审核通过后才能发布职位");
            requireCompanyInfo(company);
            j.setStatus("APPROVED"); j.setPublishedAt(now());
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
    /** 发布前校验真实企业档案，防止绕过前端直接调用发布接口。 */
    private void requireCompanyInfo(Profile p) {
        List<String> missing=new ArrayList<>();
        if(p.getCompanyName()==null||p.getCompanyName().trim().length()<2) missing.add("企业名称");
        if(p.getIndustry()==null||p.getIndustry().isBlank()) missing.add("行业");
        if(p.getCompanySize()==null||!Set.of("UNDER_20","20_99","100_499","500_999","1000_9999","10000_PLUS").contains(p.getCompanySize())) missing.add("公司规模");
        if(p.getCity()==null||p.getCity().isBlank()) missing.add("所在城市");
        if(p.getCompanyDescription()==null||p.getCompanyDescription().isBlank()) missing.add("公司简介");
        if(!missing.isEmpty()) state("发布前请完善企业资料："+String.join("、",missing));
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
        return b.object("id",id.toString(),"companyName",p.getCompanyName(),"industry",p.getIndustry(),"companySize",p.getCompanySize(),"city",p.getCity(),"companyDescription",p.getCompanyDescription(),"avatarUrl",p.getAvatarPath()==null?null:"/api/users/"+id+"/avatar");
    }
    /** 公司展示字段实时补充，避免Redis职位缓存保留旧企业资料。 */
    private void companyFields(ObjectNode out,Profile p) {
        out.put("companyName",p==null?null:p.getCompanyName());
        out.put("companyIndustry",p==null?null:p.getIndustry());
        out.put("companySize",p==null?null:p.getCompanySize());
        out.put("companyAvatarUrl",p==null||p.getAvatarPath()==null?null:"/api/users/"+p.getId()+"/avatar");
    }
}
