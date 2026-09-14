package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.PageResult;
import cn.itcast.demo.jobplatform.dto.RecruitmentRequests.*;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import cn.itcast.demo.jobplatform.vo.AuthViews;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** 个人资料、企业审核与档案启停；账号与多身份档案不混用。 */
@Service
public class ProfileService {
    private final ProfileMapper profiles;
    private final AccountMapper accounts;
    private final ResumeMapper resumes;
    private final AccountService accountService;
    private final BusinessSupport b;
    private final BusinessRedis redis;
    private final VectorSyncService vectors;
    private final FileStorageService files;
    private final AuditService audit;
    public ProfileService(ProfileMapper profiles,AccountMapper accounts,ResumeMapper resumes,AccountService accountService,BusinessSupport b,BusinessRedis redis,VectorSyncService vectors,FileStorageService files,AuditService audit) {
        this.profiles=profiles; this.accounts=accounts; this.resumes=resumes; this.accountService=accountService; this.b=b; this.redis=redis; this.vectors=vectors; this.files=files; this.audit=audit;
    }
    public AuthViews.User view(Profile p) { return accountService.user(accounts.selectById(p.getAccountId()),p); }
    @Transactional
    public AuthViews.User personal(Personal input,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER");
        Profile update=new Profile(); update.setName(input.name().trim()); update.setEducation(input.education());
        profiles.update(update,new UpdateWrapper<Profile>().eq("id",p.getId()).set("city",trim(input.city())).set("introduction",trim(input.introduction())));
        return view(profiles.selectById(p.getId()));
    }
    @Transactional
    public AuthViews.User company(Company input,HttpServletRequest request) {
        if(input.companyName().trim().length()<2) bad("企业名称至少2个字");
        Profile p=b.actor(request,"COMPANY"); Profile update=new Profile(); update.setCompanyName(input.companyName().trim()); update.setReviewStatus("PENDING");
        profiles.update(update,new UpdateWrapper<Profile>().eq("id",p.getId()).set("industry",trim(input.industry())).set("company_size",input.companySize()).set("city",trim(input.city())).set("company_description",trim(input.companyDescription())).set("review_reason",null));
        return view(profiles.selectById(p.getId()));
    }
    @Transactional
    public ObjectNode submit(HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY");
        Profile locked=profiles.selectOne(new QueryWrapper<Profile>().eq("id",p.getId()).last("FOR UPDATE"));
        if(!"REJECTED".equals(locked.getReviewStatus())) state("仅被拒绝企业可重新提交审核");
        Profile update=new Profile(); update.setReviewStatus("PENDING"); profiles.update(update,new UpdateWrapper<Profile>().eq("id",p.getId()).set("review_reason",null)); return b.object("reviewStatus","PENDING");
    }
    @Transactional
    public ObjectNode discover(Discoverability input,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER");
        Profile update=new Profile(); update.setId(p.getId()); update.setDiscoverable(input.discoverable()); profiles.updateById(update);
        Resume r=resumes.selectOne(new QueryWrapper<Resume>().eq("candidate_id",p.getId()).eq("is_current",true).last("FOR UPDATE"));
        if(r!=null && "SUCCESS".equals(r.getParseStatus())) vectors.enqueue(r,!input.discoverable());
        return b.object("discoverable",input.discoverable(),"indexStatus",r==null?"NOT_READY":r.getIndexStatus());
    }
    public ObjectNode avatar(MultipartFile file,HttpServletRequest request) {
        Profile p=b.actor(request); redis.limit(p.getId(),"avatar",20,3600); var saved=files.save(file,false);
        Profile update=new Profile(); update.setId(p.getId()); update.setAvatarPath(saved.path()); profiles.updateById(update);
        return b.object("avatarUrl","/api/users/"+p.getId()+"/avatar");
    }
    public ResponseEntity<Resource> avatar(Long id) {
        Profile p=profiles.selectById(id); if(p==null||!b.available(p)) missing(); return files.download(p.getAvatarPath(),"avatar",false);
    }
    public PageResult<AuthViews.User> list(Map<String,String> q,HttpServletRequest request) {
        b.actor(request,"ADMIN"); QueryWrapper<Profile> w=new QueryWrapper<>();
        for(String key:List.of("role","reviewStatus")) if(q.containsKey(key)&&!q.get(key).isBlank()) w.eq(key.equals("role")?"role":"review_status",q.get(key));
        if(q.containsKey("enabled")) { if(!Set.of("true","false").contains(q.get("enabled"))) bad("enabled须为布尔值"); w.eq("enabled",Boolean.parseBoolean(q.get("enabled"))); }
        String word=q.get("keyword"); if(word!=null&&!word.isBlank()) w.and(n->n.like("name",word).or().like("company_name",word).or().apply("account_id in (select id from account where phone like {0} or username like {0})","%"+word+"%"));
        Page<Profile> page=profiles.selectPage(new Page<>(page(q),size(q)),w.orderByDesc("created_at","id"));
        return new PageResult<>(page.getRecords().stream().map(this::view).toList(),page.getTotal(),page.getCurrent(),page.getSize());
    }
    public AuthViews.User detail(Long id,HttpServletRequest request) { b.actor(request,"ADMIN"); Profile p=profiles.selectById(id); if(p==null) missing(); return view(p); }
    @Transactional
    public AuthViews.User manage(Long id,Review review,Enabled enabled,HttpServletRequest request) {
        Profile actor=b.actor(request,"ADMIN"); Profile p=profiles.selectOne(new QueryWrapper<Profile>().eq("id",id).last("FOR UPDATE"));
        if(p==null) missing(); if("ADMIN".equals(p.getRole())) forbidden();
        ObjectNode before=b.object("reviewStatus",p.getReviewStatus(),"enabled",p.getEnabled()); String reason,action;
        if(review!=null) {
            if(!"COMPANY".equals(p.getRole())||!"PENDING".equals(p.getReviewStatus())) state("仅待审企业可审核");
            if("REJECTED".equals(review.decision())&&(review.reason()==null||review.reason().isBlank())) bad("拒绝时必须填写原因");
            p.setReviewStatus(review.decision()); p.setReviewReason(trim(review.reason())); reason=trim(review.reason()); action=review.decision();
            profiles.update(p,new UpdateWrapper<Profile>().eq("id",id).set("review_reason",reason));
        } else {
            p.setEnabled(enabled.enabled()); profiles.updateById(p); reason=enabled.reason(); action=enabled.enabled()?"ENABLE":"DISABLE";
            if("JOB_SEEKER".equals(p.getRole())) {
                Resume r=resumes.selectOne(new QueryWrapper<Resume>().eq("candidate_id",id).eq("is_current",true).last("FOR UPDATE"));
                if(r!=null&&"SUCCESS".equals(r.getParseStatus())) vectors.enqueue(r,!enabled.enabled()||!Boolean.TRUE.equals(p.getDiscoverable()));
            }
        }
        audit.record(actor.getId(),"PROFILE",id,action,reason,before,b.object("reviewStatus",p.getReviewStatus(),"enabled",p.getEnabled())); return view(p);
    }
}
