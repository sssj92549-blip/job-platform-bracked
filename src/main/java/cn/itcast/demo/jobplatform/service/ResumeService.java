package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.*;
import cn.itcast.demo.jobplatform.dto.RecruitmentRequests.*;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** 简历版本、用户确认及解析任务。任何异步回写必须匹配启动版本。 */
@Service
public class ResumeService {
    private final ResumeMapper resumes;
    private final ProfileRepository profiles;
    private final AccountMapper accounts;
    private final BusinessSupport b;
    private final BusinessRedis redis;
    private final FileStorageService files;
    private final VectorSyncService vectors;
    private final PythonAiClient python;
    private final TransactionTemplate tx;
    public ResumeService(ResumeMapper resumes,ProfileRepository profiles,AccountMapper accounts,BusinessSupport b,BusinessRedis redis,FileStorageService files,VectorSyncService vectors,PythonAiClient python,PlatformTransactionManager tm) {
        this.resumes=resumes; this.profiles=profiles; this.accounts=accounts; this.b=b; this.redis=redis; this.files=files; this.vectors=vectors; this.python=python; tx=new TransactionTemplate(tm);
    }
    public Resume own(Long id,Long candidate,boolean lock) {
        Resume r=resumes.selectOne(new QueryWrapper<Resume>().eq("id",id).eq("candidate_id",candidate).eq("is_current",true).last(lock?"FOR UPDATE":""));
        if(r==null) missing(); return r;
    }
    public Resume confirmed(Long id,Integer version,Long candidate) {
        Resume r=own(id,candidate,true); version(Objects.equals(r.getVersion(),version));
        if(!"SUCCESS".equals(r.getParseStatus())||!"CONFIRMED".equals(r.getConfirmationStatus())) state("请先解析并确认当前简历"); return r;
    }
    public ObjectNode view(Resume r) {
        if(r==null) return null;
        ObjectNode out=b.view(r,"candidateId","isCurrent","deleted","filePath","originalProfile","parseStartedAt");
        for(String name:List.of("parsedSkills","conflicts","confirmedProfile","parsedWorkExperience","parsedInternshipExperience","parsedProjectExperience","parsedCampusExperience","parsedCertificates")) out.set(name,b.read(out.path(name).isNull()?null:out.path(name).asText()));
        if(out.path("parsedSkills").isNull()) out.putArray("parsedSkills"); if(out.path("conflicts").isNull()) out.putArray("conflicts");
        out.put("downloadUrl","/api/resumes/"+r.getId()+"/file"); return out;
    }
    public ObjectNode current(HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER"); return view(resumes.selectOne(new QueryWrapper<Resume>().eq("candidate_id",p.getId()).eq("is_current",true)));
    }
    public ObjectNode detail(Long id,HttpServletRequest request) { return view(own(id,b.actor(request,"JOB_SEEKER").getId(),false)); }
    public ObjectNode upload(MultipartFile file,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER"); redis.limit(p.getId(),"upload",10,3600);
        String key="resume:"+p.getId(),token=redis.lock(key);
        try {
            FileStorageService.Stored stored=files.save(file,true);
            return tx.execute(s->{
                profiles.selectOne(new QueryWrapper<Profile>().eq("id",p.getId()).last("FOR UPDATE"));
                Resume old=resumes.selectOne(new QueryWrapper<Resume>().eq("candidate_id",p.getId()).eq("is_current",true).last("FOR UPDATE"));
                if(old!=null) { old.setIsCurrent(false); resumes.updateById(old); vectors.enqueue(old,true); }
                Resume r=new Resume(); r.setCandidateId(p.getId()); r.setVersion(1); r.setIsCurrent(true); r.setDeleted(false);
                r.setFileName(stored.name()); r.setFilePath(stored.path()); r.setFileSize(stored.size());
                r.setParseStatus("PENDING"); r.setConfirmationStatus("UNCONFIRMED"); r.setConflictStatus("NONE"); r.setIndexStatus("NOT_READY");
                r.setOriginalProfile(b.write(b.object("name",p.getName(),"phone",accounts.selectById(p.getAccountId()).getPhone(),"education",p.getEducation())));
                resumes.insert(r); return accepted(r);
            });
        } finally { redis.unlock(key,token); }
    }
    private ObjectNode accepted(Resume r) { return b.object("resumeId",r.getId().toString(),"version",r.getVersion(),"parseStatus",r.getParseStatus(),"confirmationStatus",r.getConfirmationStatus(),"indexStatus",r.getIndexStatus()); }
    @Transactional
    public ObjectNode confirm(Long id,Confirm input,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER"); Resume r=own(id,p.getId(),true); version(r.getVersion().equals(input.expectedVersion()));
        if(!"SUCCESS".equals(r.getParseStatus())) state("解析成功后才可确认");
        vectors.enqueue(r,true); r.setVersion(r.getVersion()+1); r.setConfirmationStatus("CONFIRMED"); r.setConfirmedAt(now());
        ObjectNode confirmed=b.object("name",input.name().trim(),"contactPhone",input.contactPhone(),"education",input.education(),"skills",input.skills(),"birthDate",input.birthDate()==null?null:input.birthDate().toString(),"workExperienceYears",input.workExperienceYears());
        ObjectNode sections=optionalSections(r);
        JsonNode previous=b.read(r.getConfirmedProfile()).path("optionalSections");
        if(previous.isObject()) sections=(ObjectNode)previous.deepCopy();
        if(input.optionalSections()!=null) {
            for(var entry:input.optionalSections().entrySet()) {
                if(!sections.has(entry.getKey())) bad("不支持的简历经历字段");
                List<String> items=entry.getValue();
                if(items!=null&&(items.size()>20||items.stream().anyMatch(v->v==null||v.isBlank()||v.length()>2000))) bad("每类经历最多20项，每项1至2000字");
                sections.set(entry.getKey(),items==null||items.isEmpty()?NullNode.instance:b.json.valueToTree(items));
            }
        }
        confirmed.set("age",b.json.valueToTree(input.age())); confirmed.put("ageAsOf",now().toLocalDate().toString());
        confirmed.set("optionalSections",sections); r.setConfirmedProfile(b.write(confirmed));
        r.setConflictStatus("RESOLVED"); resumes.updateById(r);
        Profile update=new Profile(); update.setId(p.getId()); update.setName(input.name().trim()); update.setEducation(input.education()); profiles.updateById(update);
        vectors.enqueue(r,false); return view(r);
    }
    @Transactional
    public ObjectNode reparse(Long id,Version input,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER"); redis.limit(p.getId(),"parse",10,3600);
        Resume r=own(id,p.getId(),true); version(r.getVersion().equals(input.expectedVersion()));
        if(!Set.of("SUCCESS","FAILED").contains(r.getParseStatus())) state("当前简历正在解析");
        vectors.enqueue(r,true); r.setVersion(r.getVersion()+1); r.setParseStatus("PENDING"); r.setConfirmationStatus("UNCONFIRMED"); r.setIndexStatus("NOT_READY"); r.setConflictStatus("NONE");
        UpdateWrapper<Resume> w=new UpdateWrapper<Resume>().eq("id",id);
        for(String col:List.of("confirmed_profile","confirmed_at","parse_error","parse_started_at","parsed_birth_date","parsed_age","parsed_work_experience_years","parsed_name","parsed_phone","parsed_education","parsed_skills","parsed_summary","parsed_work_experience","parsed_internship_experience","parsed_project_experience","parsed_campus_experience","parsed_certificates","extracted_text","conflicts","index_error")) w.set(col,null);
        // Wrapper负责明确清空字段，实体负责更新状态和自动更新时间。
        Resume update=new Resume(); update.setVersion(r.getVersion()); update.setParseStatus("PENDING"); update.setConfirmationStatus("UNCONFIRMED"); update.setIndexStatus("NOT_READY"); update.setConflictStatus("NONE"); resumes.update(update,w);
        return accepted(r);
    }
    @Transactional
    public ObjectNode retry(Long id,Version input,HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER"); redis.limit(p.getId(),"index",10,3600);
        Resume r=own(id,p.getId(),true); version(r.getVersion().equals(input.expectedVersion()));
        if(!"SUCCESS".equals(r.getParseStatus())||!"FAILED".equals(r.getIndexStatus())) state("仅失败的索引可重试"); vectors.enqueue(r,false); return accepted(r);
    }
    @Transactional
    public ObjectNode delete(Long id,HttpServletRequest request) {
        Resume r=own(id,b.actor(request,"JOB_SEEKER").getId(),true); vectors.enqueue(r,true); resumes.deleteById(id); return b.object();
    }
    public ResponseEntity<Resource> download(Long id,HttpServletRequest request) {
        Resume r=own(id,b.actor(request,"JOB_SEEKER").getId(),false); return files.download(r.getFilePath(),r.getFileName(),true);
    }
    /** 后台同步调用Python，不持有数据库事务；完成后短事务写回指定版本。 */
    public void processOne() {
        Resume r=resumes.selectOne(new QueryWrapper<Resume>().eq("is_current",true).eq("parse_status","PENDING").orderByAsc("id").last("LIMIT 1"));
        if(r==null) return;
        Resume processing=new Resume(); processing.setParseStatus("PROCESSING"); processing.setParseStartedAt(now());
        if(resumes.update(processing,new UpdateWrapper<Resume>().eq("id",r.getId()).eq("version",r.getVersion()).eq("parse_status","PENDING"))!=1) return;
        try {
            JsonNode result=python.call(HttpMethod.POST,"/internal/resumes/parse",b.object("resumeId",r.getId().toString(),"resumeVersion",r.getVersion(),"filePath",r.getFilePath()));
            validateParse(r,result);
            tx.executeWithoutResult(s->{
                Resume fresh=resumes.selectOne(new QueryWrapper<Resume>().eq("id",r.getId()).eq("version",r.getVersion()).eq("is_current",true).eq("parse_status","PROCESSING").last("FOR UPDATE"));
                if(fresh==null) return;
                fresh.setParseStatus("SUCCESS"); fresh.setExtractedText(result.path("extractedText").asText()); fresh.setExtractionMethod(result.path("extractionMethod").asText()); fresh.setPageCount(result.path("pageCount").asInt());
                fresh.setParsedName(text(result,"parsedName")); fresh.setParsedPhone(text(result,"parsedPhone")); fresh.setParsedEducation(text(result,"parsedEducation")); fresh.setParsedSummary(text(result,"parsedSummary")); fresh.setParsedSkills(result.path("parsedSkills").toString());
                fresh.setParsedBirthDate(result.hasNonNull("parsedBirthDate") ? java.time.LocalDate.parse(result.path("parsedBirthDate").asText()) : null);
                if(fresh.getParsedBirthDate()!=null && fresh.getParsedBirthDate().isAfter(now().toLocalDate())) PythonAiClient.invalid();
                fresh.setParsedAge(optionalInteger(result,"parsedAge",120));
                fresh.setParsedWorkExperienceYears(optionalInteger(result,"parsedWorkExperienceYears",60));
                fresh.setParsedWorkExperience(optionalArray(result,"parsedWorkExperience"));
                fresh.setParsedInternshipExperience(optionalArray(result,"parsedInternshipExperience"));
                fresh.setParsedProjectExperience(optionalArray(result,"parsedProjectExperience"));
                fresh.setParsedCampusExperience(optionalArray(result,"parsedCampusExperience"));
                fresh.setParsedCertificates(optionalArray(result,"parsedCertificates"));
                ArrayNode conflicts=b.json.createArrayNode(); JsonNode original=b.read(fresh.getOriginalProfile());
                for(String field:List.of("name","phone","education")) {
                    String ai=text(result,"parsed"+Character.toUpperCase(field.charAt(0))+field.substring(1)); String user=text(original,field);
                    if(ai!=null&&!ai.isBlank()&&!Objects.equals(trim(user),trim(ai))) conflicts.add(b.object("field",field,"userValue",user,"aiValue",ai));
                }
                fresh.setConflicts(conflicts.toString()); fresh.setConflictStatus(conflicts.isEmpty()?"NONE":"PENDING_VERIFY"); resumes.updateById(fresh); vectors.enqueue(fresh,false);
            });
        } catch(Exception e) {
            Resume failed=new Resume(); failed.setParseStatus("FAILED"); failed.setParseError(e instanceof BusinessException?e.getMessage():"简历解析失败，请重试");
            resumes.update(failed,new UpdateWrapper<Resume>().eq("id",r.getId()).eq("version",r.getVersion()).eq("parse_status","PROCESSING"));
        }
    }
    /** 可选经历按数组存储；兼容旧AI服务缺少字段，统一返回NULL。 */
    /** 验证可选数字，拒绝字符串、负数和越界的模型结果。 */
    private static Integer optionalInteger(JsonNode result,String field,int max) {
        JsonNode n=result.path(field); if(n.isMissingNode()||n.isNull()) return null;
        if(!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<0||n.asInt()>max) PythonAiClient.invalid();
        return n.asInt();
    }
    private static String optionalArray(JsonNode result,String field) {
        JsonNode value=result.path(field);
        if(value.isMissingNode()||value.isNull()) return null;
        if(!value.isArray()||value.size()>20) PythonAiClient.invalid();
        for(JsonNode item:value) if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>2000) PythonAiClient.invalid();
        return value.isEmpty()?null:value.toString();
    }
    /** 供投递快照复用的五类可选解析信息。 */
    public ObjectNode optionalSections(Resume r) {
        return b.object("parsedWorkExperience",b.read(r.getParsedWorkExperience()),"parsedInternshipExperience",b.read(r.getParsedInternshipExperience()),"parsedProjectExperience",b.read(r.getParsedProjectExperience()),"parsedCampusExperience",b.read(r.getParsedCampusExperience()),"parsedCertificates",b.read(r.getParsedCertificates()));
    }
    private static String text(JsonNode n,String key) { return n.hasNonNull(key)?n.path(key).asText():null; }
    private static void validateParse(Resume r,JsonNode result) {
        if(!result.path("resumeId").asText().equals(r.getId().toString())||!result.path("resumeVersion").isIntegralNumber()||result.path("resumeVersion").asInt()!=r.getVersion()
            ||!result.path("extractedText").isTextual()||result.path("extractedText").asText().isBlank()||result.path("extractedText").asText().length()>60000
            ||!Set.of("TEXT","OCR","MIXED").contains(result.path("extractionMethod").asText())||!result.path("pageCount").isIntegralNumber()||result.path("pageCount").asInt()<1||result.path("pageCount").asInt()>20
            ||!result.path("parsedSkills").isArray()||result.path("parsedSkills").size()>50) PythonAiClient.invalid();
        for(JsonNode skill:result.path("parsedSkills")) if(!skill.isTextual()||skill.asText().isBlank()||skill.asText().length()>100) PythonAiClient.invalid();
        for(var field:Map.of("parsedName",50,"parsedPhone",32,"parsedSummary",2000).entrySet()) {
            JsonNode value=result.path(field.getKey()); if(value.isMissingNode()||(!value.isNull()&&(!value.isTextual()||value.asText().length()>field.getValue()))) PythonAiClient.invalid();
        }
        for(String field:List.of("parsedWorkExperience","parsedInternshipExperience","parsedProjectExperience","parsedCampusExperience","parsedCertificates")) optionalArray(result,field);
        JsonNode education=result.path("parsedEducation");
        if(education.isMissingNode()||(!education.isNull()&&!Set.of("HIGH_SCHOOL","JUNIOR_COLLEGE","BACHELOR","MASTER","DOCTOR","OTHER").contains(education.asText()))) PythonAiClient.invalid();
    }
    public void recover() {
        Resume failed=new Resume(); failed.setParseStatus("FAILED"); failed.setParseError("解析任务中断或超时，请重新解析");
        resumes.update(failed,new UpdateWrapper<Resume>().eq("parse_status","PROCESSING").lt("parse_started_at",now().minusMinutes(10)));
    }
}
