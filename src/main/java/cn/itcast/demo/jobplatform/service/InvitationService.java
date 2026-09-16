package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.*;
import cn.itcast.demo.jobplatform.dto.InvitationRequests.*;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** 邀请与通知同事务落库；按职位行锁串行化发送与实际投递。 */
@Service
public class InvitationService {
    private static final List<String> OPEN = List.of("UNVIEWED", "PENDING");
    private final InvitationMapper invitations;
    private final NotificationMapper notifications;
    private final JobMapper jobs;
    private final ApplicationMapper applications;
    private final ResumeMapper resumes;
    private final ProfileRepository profiles;
    private final BusinessSupport b;
    private final BusinessRedis redis;

    public InvitationService(InvitationMapper invitations, NotificationMapper notifications, JobMapper jobs,
            ApplicationMapper applications, ResumeMapper resumes, ProfileRepository profiles, BusinessSupport b, BusinessRedis redis) {
        this.invitations=invitations; this.notifications=notifications; this.jobs=jobs;
        this.applications=applications; this.resumes=resumes; this.profiles=profiles; this.b=b; this.redis=redis;
    }

    private Job job(Long id) {
        Job j=jobs.selectOne(new QueryWrapper<Job>().eq("id",id).last("FOR UPDATE"));
        if(j==null) missing();
        return j;
    }
    private void published(Job j) {
        if(!"APPROVED".equals(j.getStatus()) || !b.available(profiles.selectById(j.getCompanyId()))) state("职位已下架或企业当前不可用");
    }
    private void notify(Invitation i, Long recipient, String title, String body) {
        Notification n=new Notification(); n.setRecipientId(recipient); n.setInvitationId(i.getId());
        n.setTitle(title); n.setBody(body); notifications.insert(n);
    }
    private Invitation create(Job j, Profile p, Long candidateId, String name, String type, String message) {
        Invitation i=new Invitation(); i.setType(type); i.setCompanyId(p.getId()); i.setCandidateId(candidateId);
        i.setJobId(j.getId()); i.setJobTitle(j.getTitle()); i.setCompanyName(p.getCompanyName());
        i.setCandidateName(name == null ? "候选人" : name); i.setStatus("UNVIEWED");
        i.setMessage(message==null ? "" : message.trim()); i.setExpiresAt(now().plusDays(3)); return i;
    }
    private void insert(Invitation i) {
        if(invitations.selectCount(new QueryWrapper<Invitation>().eq("active_key",i.getActiveKey()))>0)
            state("已有待处理或已接受的邀请，请查看邀请记录");
        // 三天内同类型同职位同一候选人最多发送一次，拒绝/撤销也不能反复骚扰。
        if(invitations.selectCount(new QueryWrapper<Invitation>().eq("type",i.getType()).eq("job_id",i.getJobId())
                .eq("candidate_id",i.getCandidateId()).gt("created_at",now().minusDays(3)))>0)
            state("三天内已发送过此类邀请，请勿重复发送");
        invitations.insert(i);
        notify(i,i.getCandidateId(),"APPLICATION".equals(i.getType()) ? "收到投递邀请" : "收到面试邀请",i.getCompanyName()+" · "+i.getJobTitle());
    }

    @Transactional
    public ObjectNode inviteApply(Long jobId, ApplyInvite input, HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY"); redis.limit(p.getId(),"invitation",30,60);
        Job j=job(jobId); if(!j.getCompanyId().equals(p.getId())) missing(); published(j);
        refreshJob(jobId);
        Profile candidate=profiles.selectById(input.candidateId());
        if(!b.available(candidate) || !"JOB_SEEKER".equals(candidate.getRole()) || !Boolean.TRUE.equals(candidate.getDiscoverable()))
            state("候选人当前不可被邀请，请重新检索");
        Resume r=resumes.selectOne(new QueryWrapper<Resume>().eq("id",input.resumeId()).eq("candidate_id",input.candidateId())
                .eq("version",input.resumeVersion()).eq("is_current",true).eq("parse_status","SUCCESS")
                .eq("confirmation_status","CONFIRMED").eq("index_status","READY"));
        if(r==null) state("候选人简历已变化，请重新检索");
        if(applications.selectCount(new QueryWrapper<Application>().eq("job_id",jobId).eq("candidate_id",candidate.getId()))>0)
            state("候选人已投递过该职位");
        Invitation i=create(j,p,candidate.getId(),b.read(r.getConfirmedProfile()).path("name").asText("候选人"),"APPLICATION",input.message());
        i.setActiveKey("APPLICATION:"+jobId+":"+candidate.getId()); insert(i); return view(i);
    }

    @Transactional
    public ObjectNode inviteInterview(Long applicationId, InterviewInvite input, HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY"); redis.limit(p.getId(),"invitation",30,60);
        Application snapshot=applications.selectById(applicationId); if(snapshot==null) missing();
        Job j=job(snapshot.getJobId()); if(!j.getCompanyId().equals(p.getId())) missing(); published(j);
        Application a=applications.selectOne(new QueryWrapper<Application>().eq("id",applicationId).last("FOR UPDATE"));
        if(!List.of("SUBMITTED","VIEWED","SHORTLISTED").contains(a.getStatus())) state("当前投递不可邀请面试");
        if(!b.available(profiles.selectById(a.getCandidateId()))) state("候选人当前不可用");
        LocalDateTime time=input.interviewAt().atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        if(!time.isAfter(now())) bad("面试时间必须晚于当前时间");
        refreshJob(j.getId());
        Invitation i=create(j,p,a.getCandidateId(),b.read(a.getResumeSnapshot()).path("confirmedProfile").path("name").asText("候选人"),"INTERVIEW",input.message());
        i.setApplicationId(a.getId()); i.setInterviewAt(time); i.setInterviewMode(input.interviewMode()); i.setLocation(input.location().trim());
        if(time.isBefore(i.getExpiresAt())) i.setExpiresAt(time);
        i.setActiveKey("INTERVIEW:"+applicationId); insert(i); return view(i);
    }

    public ObjectNode view(Invitation i) { return b.view(i,"activeKey"); }

    private Invitation own(Long id, Profile p) {
        Invitation i=invitations.selectById(id);
        if(i==null || !("COMPANY".equals(p.getRole()) ? i.getCompanyId() : i.getCandidateId()).equals(p.getId())) missing();
        return i;
    }

    @Transactional
    public PageResult<ObjectNode> list(Map<String,String> q,HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY","JOB_SEEKER");
        QueryWrapper<Invitation> w=new QueryWrapper<Invitation>().eq("COMPANY".equals(p.getRole())?"company_id":"candidate_id",p.getId());
        for(String key:List.of("jobId","applicationId","candidateId")) if(q.containsKey(key) && !q.get(key).isBlank()) {
            String col=switch(key){case "jobId"->"job_id"; case "applicationId"->"application_id"; default->"candidate_id";}; w.eq(col,q.get(key));
        }
        if(q.containsKey("type") && !q.get("type").isBlank()) w.eq("type",q.get("type"));
        Page<Invitation> result=invitations.selectPage(new Page<>(page(q),size(q)),w.orderByDesc("created_at","id"));
        for(Invitation i:result.getRecords()) refresh(i);
        return new PageResult<>(result.getRecords().stream().map(this::view).toList(),result.getTotal(),result.getCurrent(),result.getSize());
    }

    @Transactional
    public ObjectNode detail(Long id,boolean opened,HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY","JOB_SEEKER"); Invitation i=own(id,p); refresh(i);
        if(opened && "JOB_SEEKER".equals(p.getRole())) {
            var time=now();
            invitations.update(null,new UpdateWrapper<Invitation>().eq("id",id).eq("status","UNVIEWED")
                    .set("status","PENDING").set("viewed_at",time).set("updated_at",time));
        }
        if(opened) notifications.update(null,new UpdateWrapper<Notification>().eq("recipient_id",p.getId()).eq("invitation_id",id).isNull("read_at").set("read_at",now()));
        return view(invitations.selectById(id));
    }

    @Transactional(noRollbackFor=BusinessException.class)
    public ObjectNode respond(Long id,Decision input,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER"); Invitation i=own(id,p);
        // 与投递、撤回及关闭使用同一加锁顺序，随后用条件更新保证只有一个操作成功。
        job(i.getJobId());
        if(i.getApplicationId()!=null) applications.selectOne(new QueryWrapper<Application>().eq("id",i.getApplicationId()).last("FOR UPDATE"));
        i=invitations.selectOne(new QueryWrapper<Invitation>().eq("id",id).last("FOR UPDATE")); refresh(i);
        if(!OPEN.contains(i.getStatus())) state("邀请已处理或已失效，请刷新");
        if("ACCEPT".equals(input.action()) && !"INTERVIEW".equals(i.getType())) bad("投递邀请需要确认简历并完成实际投递");
        String status="ACCEPT".equals(input.action())?"ACCEPTED":"REJECTED";
        if(!change(i,status,null)) state("邀请状态已变化，请刷新");
        notifications.update(null,new UpdateWrapper<Notification>().eq("recipient_id",p.getId()).eq("invitation_id",id).isNull("read_at").set("read_at",now()));
        notify(i,i.getCompanyId(),"ACCEPTED".equals(status)?"候选人已接受面试":"候选人已拒绝邀请",i.getCandidateName()+" · "+i.getJobTitle());
        return view(i);
    }

    @Transactional(noRollbackFor=BusinessException.class)
    public ObjectNode cancel(Long id,HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY"); Invitation i=own(id,p); job(i.getJobId());
        i=invitations.selectOne(new QueryWrapper<Invitation>().eq("id",id).last("FOR UPDATE")); refresh(i);
        if(!OPEN.contains(i.getStatus()) && !"ACCEPTED".equals(i.getStatus())) state("当前邀请不可撤销");
        if(change(i,"EXPIRED","企业已撤销邀请")) notify(i,i.getCandidateId(),"企业已撤销邀请",i.getCompanyName()+" · "+i.getJobTitle());
        return view(i);
    }

    private boolean change(Invitation i,String status,String reason) {
        UpdateWrapper<Invitation> expected=new UpdateWrapper<Invitation>().eq("id",i.getId());
        if(OPEN.contains(i.getStatus())) expected.in("status",OPEN);
        else expected.eq("status",i.getStatus());
        if(List.of("ACCEPTED","REJECTED","APPLIED").contains(status)) expected.gt("expires_at",now());
        int count=invitations.update(null,expected
                .set("status",status).set("invalid_reason",reason).set("responded_at",now()).set("updated_at",now())
                .set("active_key","ACCEPTED".equals(status)?i.getActiveKey():null));
        Invitation latest=invitations.selectById(i.getId());
        i.setStatus(latest.getStatus()); i.setInvalidReason(latest.getInvalidReason()); i.setRespondedAt(latest.getRespondedAt()); i.setUpdatedAt(latest.getUpdatedAt());
        return count==1;
    }

    /** 每次读取/操作校验；后台每分钟清理。已完成的用户处理不会因三天到期被覆盖。 */
    private void refresh(Invitation i) {
        if(!OPEN.contains(i.getStatus()) && !"ACCEPTED".equals(i.getStatus())) return;
        Job j=jobs.selectById(i.getJobId()); String reason=null;
        if(j==null || !"APPROVED".equals(j.getStatus()) || !b.available(profiles.selectById(i.getCompanyId()))) reason="职位已下架或企业不可用";
        else if(!b.available(profiles.selectById(i.getCandidateId()))) reason="候选人账号不可用";
        else if("INTERVIEW".equals(i.getType())) {
            Application a=applications.selectById(i.getApplicationId());
            if(a==null || List.of("WITHDRAWN","REJECTED").contains(a.getStatus())) reason="投递已撤回或未通过筛选";
        }
        if(reason==null && OPEN.contains(i.getStatus()) && !i.getExpiresAt().isAfter(now())) reason="INTERVIEW".equals(i.getType()) && !i.getInterviewAt().isAfter(now()) ? "面试时间已到，邀请未处理" : "发出后满三天未处理";
        if(reason!=null) change(i,"EXPIRED",reason);
        else {
            Invitation latest=invitations.selectById(i.getId()); i.setStatus(latest.getStatus()); i.setViewedAt(latest.getViewedAt());
            i.setRespondedAt(latest.getRespondedAt()); i.setInvalidReason(latest.getInvalidReason());
        }
    }
    public void refreshJob(Long jobId) {
        for(Invitation i:invitations.selectList(new QueryWrapper<Invitation>().eq("job_id",jobId).in("status",List.of("UNVIEWED","PENDING","ACCEPTED")))) refresh(i);
    }
    public void applied(Application a) {
        for(Invitation i:invitations.selectList(new QueryWrapper<Invitation>().eq("type","APPLICATION").eq("job_id",a.getJobId()).eq("candidate_id",a.getCandidateId()).in("status",OPEN))) {
            refresh(i);
            if(OPEN.contains(i.getStatus()) && change(i,"APPLIED",null)) {
                invitations.update(null,new UpdateWrapper<Invitation>().eq("id",i.getId()).set("application_id",a.getId()));
                notify(i,i.getCompanyId(),"候选人已完成投递",i.getCandidateName()+" · "+i.getJobTitle());
            }
        }
    }
    public void validateApply(Long id, Profile p, Long jobId) {
        Invitation i=own(id,p); refresh(i);
        if(!"APPLICATION".equals(i.getType()) || !i.getJobId().equals(jobId)) bad("邀请与投递职位不匹配");
        if(!OPEN.contains(i.getStatus())) state("邀请已处理或已失效，请刷新");
    }
    public void invalidateApplication(Long applicationId) {
        for(Invitation i:invitations.selectList(new QueryWrapper<Invitation>().eq("type","INTERVIEW").eq("application_id",applicationId).in("status",List.of("UNVIEWED","PENDING","ACCEPTED")))) {
            if(change(i,"EXPIRED","投递已撤回或未通过筛选")) notify(i,i.getCandidateId(),"面试邀请已失效",i.getCompanyName()+" · "+i.getJobTitle());
        }
    }
    @Transactional
    public void expire() {
        // 游标分批，不让大量历史邀请一次驻留内存；重复执行通过条件更新保持幂等。
        long cursor=0;
        while(true) {
            List<Invitation> batch=invitations.selectList(new QueryWrapper<Invitation>().gt("id",cursor).in("status",List.of("UNVIEWED","PENDING","ACCEPTED")).orderByAsc("id").last("LIMIT 200"));
            if(batch.isEmpty()) break;
            for(Invitation i:batch) { refresh(i); cursor=i.getId(); }
        }
    }
    public PageResult<ObjectNode> notifications(Map<String,String> q,HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY","JOB_SEEKER");
        Page<Notification> page=notifications.selectPage(new Page<>(page(q),size(q)),new QueryWrapper<Notification>().eq("recipient_id",p.getId()).orderByDesc("created_at","id"));
        return new PageResult<>(page.getRecords().stream().map(n->b.view(n)).toList(),page.getTotal(),page.getCurrent(),page.getSize());
    }
    public ObjectNode unread(HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY","JOB_SEEKER");
        return b.object("count",notifications.selectCount(new QueryWrapper<Notification>().eq("recipient_id",p.getId()).isNull("read_at")));
    }
    public ObjectNode read(Long id,HttpServletRequest request) {
        Profile p=b.actor(request,"COMPANY","JOB_SEEKER");
        if(id!=null && notifications.selectCount(new QueryWrapper<Notification>().eq("id",id).eq("recipient_id",p.getId()))==0) missing();
        notifications.update(null,new UpdateWrapper<Notification>().eq("recipient_id",p.getId()).eq(id!=null,"id",id).isNull("read_at").set("read_at",now()));
        return b.object();
    }
}
