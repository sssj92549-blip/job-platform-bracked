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
    private final JobRecommendationService recommendations;
    private final JobVectorService vectors;
    private final ProfileRepository profiles;
    private final ApplicationMapper applications;
    private final BusinessSupport b;
    private final BusinessRedis redis;
    private final AuditService audit;
    private static final String PUBLIC_COMPANIES="select p.id from profile_details p join account a on a.id=p.account_id where p.role='COMPANY' and p.enabled=1 and a.enabled=1 and p.review_status='APPROVED'";
    public JobService(JobRecommendationService recommendations,JobVectorService vectors,JobMapper jobs,ProfileRepository profiles,ApplicationMapper applications,BusinessSupport b,BusinessRedis redis,AuditService audit) {
        this.recommendations=recommendations; this.vectors=vectors; this.jobs=jobs; this.profiles=profiles; this.applications=applications; this.b=b; this.redis=redis; this.audit=audit;
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
        if(publicView) out.putNull("reviewReason");
        else { var index=vectors.latest(j.getId()); out.put("indexStatus",index.isEmpty()?"NOT_READY":String.valueOf(index.get("status"))); out.put("indexError",(String)index.get("error_message")); } return out;
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
        if(!"PUBLIC".equals(scope) && keyword!=null && !keyword.isEmpty()) w.and(n->n.like("title",keyword).or().apply("company_id in (select id from profile_details where company_name like {0})","%"+keyword+"%"));
        if(q.containsKey("city")&&!q.get("city").isBlank()) w.eq("city",q.get("city"));
        if(q.containsKey("education")&&!q.get("education").isBlank()) {
            String education=q.get("education");
            List<String> levels=List.of("HIGH_SCHOOL","JUNIOR_COLLEGE","BACHELOR","MASTER","DOCTOR");
            if("OTHER".equals(education)) w.eq("education_requirement","OTHER");
            else {
                int minimum=levels.indexOf(education); if(minimum<0) bad("学历筛选无效");
                w.in("education_requirement",levels.subList(minimum,levels.size()));
            }
        }
        if(q.containsKey("industry")&&!q.get("industry").isBlank()) {
            if(q.get("industry").length()>100) bad("行业筛选最多100字");
            if(!"PUBLIC".equals(scope)) w.apply("company_id in (select id from profile_details where industry like {0})","%"+q.get("industry").trim()+"%");
        }
        if(q.containsKey("companySize")&&!q.get("companySize").isBlank()) {
            if(!Set.of("UNDER_20","20_99","100_499","500_999","1000_9999","10000_PLUS").contains(q.get("companySize"))) bad("公司规模无效");
            w.apply("company_id in (select id from profile_details where company_size={0})",q.get("companySize"));
        }
        if(q.containsKey("experience")&&!q.get("experience").isBlank()) {
            switch(q.get("experience")) {
                case "ENTRY" -> w.eq("experience_min_years",0);
                case "1_3" -> w.ge("experience_min_years",1).lt("experience_min_years",3);
                case "3_5" -> w.ge("experience_min_years",3).lt("experience_min_years",5);
                case "5_PLUS" -> w.ge("experience_min_years",5);
                default -> bad("工作经验筛选无效");
            }
        }
        if(q.containsKey("salaryMin")) w.ge("salary_max",number(q,"salaryMin",0,0,1000000));
        if(q.containsKey("salaryMax")) w.le("salary_min",number(q,"salaryMax",0,0,1000000));
        if(q.containsKey("salaryMin")&&q.containsKey("salaryMax") && Integer.parseInt(q.get("salaryMin"))>Integer.parseInt(q.get("salaryMax"))) bad("薪资下限不能大于上限");
        if("PUBLIC".equals(scope)&&"recommended".equals(q.get("mode"))) return recommended(q,w,request,keyword);
        if("PUBLIC".equals(scope)&&((keyword!=null&&!keyword.isBlank())||(q.get("industry")!=null&&!q.get("industry").isBlank()))) return hybrid(q,w,keyword);
        Page<Job> page=jobs.selectPage(new Page<>(page(q),size(q)),w.orderByDesc("created_at","id"));
        return new PageResult<>(page.getRecords().stream().map(j->view(j,"PUBLIC".equals(scope))).toList(),page.getTotal(),page.getCurrent(),page.getSize());
    }
    /** 推荐tab沿用全部筛选条件，按本人已确认简历的向量相似度分页。 */
    private PageResult<ObjectNode> recommended(Map<String,String> q,QueryWrapper<Job> w,HttpServletRequest request,String keyword) {
        String context=recommendations.currentContext(request);
        List<Job> allowed=jobs.selectList(w.clone().orderByDesc("id").last("LIMIT 10001"));
        if(allowed.size()>10000) state("请先缩小筛选范围");
        Map<Long,Double> scores=recommendations.rank(context,allowed);
        String industry=trim(q.get("industry"));
        if(industry!=null&&!industry.isBlank()) scores.keySet().retainAll(rank(industry,allowed,false).keySet());
        if(keyword!=null&&!keyword.isBlank()) { if(keyword.length()>200) bad("搜索词最多200字"); scores.keySet().retainAll(rank(keyword,allowed,true).keySet()); }
        Map<Long,Integer> versions=new HashMap<>(); for(Job j:allowed) versions.put(j.getId(),j.getVersion());
        List<Job> current=new ArrayList<>(jobs.selectList(w.clone()));
        current.removeIf(j->!scores.containsKey(j.getId())||!Objects.equals(versions.get(j.getId()),j.getVersion()));
        current.sort(Comparator.<Job>comparingDouble(j->scores.get(j.getId())).reversed().thenComparing(Job::getId));
        // 推荐是精选结果，不把整库达到宽泛召回阈值的职位全部列出。
        if(!current.isEmpty()) {
            double cutoff=Math.max(0.65,scores.get(current.get(0).getId())-0.08);
            current.removeIf(j->scores.get(j.getId())<cutoff);
        }
        return new PageResult<>(current.stream().skip((page(q)-1)*size(q)).limit(size(q)).map(j->view(j,true)).toList(),current.size(),page(q),size(q));
    }
    /** 先应用业务筛选，再融合字面与向量命中；最后复查状态并分页。 */
    private PageResult<ObjectNode> hybrid(Map<String,String> q,QueryWrapper<Job> w,String keyword) {
        if(keyword!=null&&keyword.length()>200) bad("搜索词最多200字");
        List<Job> eligible=jobs.selectList(w.clone().orderByDesc("created_at","id").last("LIMIT 10001"));
        if(eligible.size()>10000) state("请先按城市或行业缩小搜索范围");
        String industry=trim(q.get("industry"));
        boolean hasIndustry=industry!=null&&!industry.isBlank();
        Map<Long,Double> scores=hasIndustry?rank(industry,eligible,false):new HashMap<>();
        if(keyword!=null&&!keyword.isBlank()) {
            Map<Long,Double> keywordScores=rank(keyword,eligible,true);
            if(hasIndustry) {
                scores.keySet().retainAll(keywordScores.keySet());
                scores.replaceAll((id,score)->score+keywordScores.get(id));
            } else scores.putAll(keywordScores);
        }
        List<Job> current=new ArrayList<>(jobs.selectList(w.clone()));
        current.removeIf(j->!scores.containsKey(j.getId())||eligible.stream().noneMatch(old->old.getId().equals(j.getId())&&old.getVersion().equals(j.getVersion())));
        current.sort(Comparator.<Job>comparingDouble(j->scores.get(j.getId())).reversed().thenComparing(Job::getId,Comparator.reverseOrder()));
        long start=(page(q)-1)*size(q);
        return new PageResult<>(current.stream().skip(start).limit(size(q)).map(j->view(j,true)).toList(),current.size(),page(q),size(q));
    }
    /** 行业选项检索岗位内容；公司名称仅参与搜索框的关键词匹配。 */
    private Map<Long,Double> rank(String query,List<Job> eligible,boolean includeCompany) {
        Map<Long,Double> scores=vectors.search(query,eligible,includeCompany?0.55:0.45);
        String needle=query.toLowerCase(Locale.ROOT);
        for(Job j:eligible) {
            String title=j.getTitle().toLowerCase(Locale.ROOT);
            String content=j.getTitle()+" "+j.getDescription()+" "+j.getRequirements()+" "+j.getSkills();
            if(includeCompany) { Profile company=profiles.selectById(j.getCompanyId()); if(company!=null) content+=" "+company.getCompanyName(); }
            if(content.toLowerCase(Locale.ROOT).contains(needle)) scores.merge(j.getId(),title.equals(needle)?3.0:title.contains(needle)?2.0:1.0,Double::sum);
        }
        return scores;
    }
    @Transactional
    public ObjectNode retryIndex(Long id,HttpServletRequest request) {
        Job j=own(id,b.actor(request,"COMPANY"),true); vectors.retry(j); return view(j,false);
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
        vectors.enqueue(j,!"APPROVED".equals(j.getStatus()));
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
        vectors.enqueue(j,true); jobs.deleteById(id); redis.evictJob(id,j.getVersion()); return b.object();
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
