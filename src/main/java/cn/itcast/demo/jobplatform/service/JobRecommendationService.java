package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** 简历驱动推荐和RAG共享检索；不读取其他求职者简历，不生成或写入人岗匹配分。 */
@Service
public class JobRecommendationService {
    private final ResumeMapper resumes;
    private final JobMapper jobs;
    private final ProfileRepository profiles;
    private final JobVectorService vectors;
    private final PythonAiClient python;
    private final BusinessSupport b;
    public JobRecommendationService(ResumeMapper resumes,JobMapper jobs,ProfileRepository profiles,JobVectorService vectors,PythonAiClient python,BusinessSupport b) {
        this.resumes=resumes; this.jobs=jobs; this.profiles=profiles; this.vectors=vectors; this.python=python; this.b=b;
    }
    /** 只索引已确认的能力信息，不把姓名、电话或年龄作为推荐依据。 */
    public String context(JsonNode confirmed) {
        StringBuilder text=new StringBuilder("学历：").append(confirmed.path("education").asText()).append("\n技能：").append(confirmed.path("skills"));
        if(confirmed.hasNonNull("workExperienceYears")) text.append("\n工作年限：").append(confirmed.path("workExperienceYears").asInt());
        for(String field:List.of("parsedWorkExperience","parsedInternshipExperience","parsedProjectExperience","parsedCampusExperience","parsedCertificates")) {
            JsonNode value=confirmed.path("optionalSections").path(field);
            if(value.isArray()) { String section=value.toString(); text.append("\n").append(field).append(":").append(section.substring(0,Math.min(4000,section.length()))); }
        }
        return text.toString();
    }
    public String currentContext(HttpServletRequest request) {
        Profile p=b.actor(request,"JOB_SEEKER");
        Resume r=resumes.selectOne(new QueryWrapper<Resume>().eq("candidate_id",p.getId()).eq("is_current",true));
        if(r==null||!"SUCCESS".equals(r.getParseStatus())||!"CONFIRMED".equals(r.getConfirmationStatus())) state("请先上传并确认简历，再查看推荐职位");
        return context(b.read(r.getConfirmedProfile()));
    }
    public Map<Long,Double> rank(String resumeText,List<Job> allowed) { return vectors.recommend("根据我的专业技能和经历推荐合适岗位",resumeText,allowed); }
    private boolean available(Job j) { return j!=null&&"APPROVED".equals(j.getStatus())&&b.available(profiles.selectById(j.getCompanyId())); }
    private ObjectNode source(Job j) {
        Profile company=profiles.selectById(j.getCompanyId());
        return b.object("jobId",j.getId().toString(),"jobVersion",j.getVersion(),"title",j.getTitle(),"companyName",company.getCompanyName(),"city",j.getCity(),"description",j.getDescription(),"requirements",j.getRequirements(),"skills",b.read(j.getSkills()),"salaryMin",j.getSalaryMin(),"salaryMax",j.getSalaryMax(),"experienceMinYears",j.getExperienceMinYears());
    }
    /** 后台工作线程先检索，再携带真实职位上下文调用大模型；网络调用不占事务。 */
    public ObjectNode answer(String question,String resumeText) { return answer(question,resumeText,b.json.createArrayNode()); }
    public ObjectNode answer(String question,String resumeText,JsonNode savedHistory) {
        JsonNode history=savedHistory.isArray()?savedHistory:b.json.createArrayNode();
        String query=question;
        Map<String,Integer> historicalVersions=new HashMap<>();
        for(JsonNode turn:history) for(JsonNode item:turn.path("sources")) historicalVersions.put(item.path("jobId").asText(),item.path("jobVersion").asInt());
        Set<String> referenced=new LinkedHashSet<>();
        if(!history.isEmpty()) {
            JsonNode resolved=python.call(HttpMethod.POST,"/internal/ai/conversation-query",b.object("question",question,"history",history));
            if(resolved==null||!resolved.path("query").isTextual()||resolved.path("query").asText().isBlank()||resolved.path("query").asText().length()>2000||!resolved.path("referencedJobIds").isArray()||resolved.path("referencedJobIds").size()>6) PythonAiClient.invalid();
            query=resolved.path("query").asText();
            for(JsonNode id:resolved.path("referencedJobIds")) if(!id.isTextual()||!historicalVersions.containsKey(id.asText())||!referenced.add(id.asText())) PythonAiClient.invalid();
        }
        List<Job> allowed=new ArrayList<>(jobs.selectList(new QueryWrapper<Job>().eq("status","APPROVED").orderByDesc("id").last("LIMIT 10001")));
        if(allowed.size()>10000) state("职位库超过单次推荐上限，请联系管理员");
        allowed.removeIf(j->!available(j));
        // 目前没有结构化远程字段，只接纳职位中明确说明可远程的证据。
        boolean remote=query.matches("(?is).*(远程|居家办公|remote).*"), entry=query.contains("应届")||query.contains("无经验");
        if(remote) allowed.removeIf(j->{ String text=(j.getTitle()+j.getDescription()+j.getRequirements()).toLowerCase(Locale.ROOT); return !(text.contains("远程")||text.contains("居家办公")||text.contains("remote"))||text.contains("不支持远程")||text.contains("不可远程")||text.contains("非远程"); });
        if(entry) allowed.removeIf(j->j.getExperienceMinYears()>0);
        if(referenced.isEmpty()) {
            Map<Long,Double> scores=vectors.recommend(query,resumeText,allowed);
            allowed.removeIf(j->!scores.containsKey(j.getId()));
            allowed.sort(Comparator.<Job>comparingDouble(j->scores.get(j.getId())).reversed().thenComparing(Job::getId));
        } else {
            // 指代历史职位时只读取那些职位的当前有效版本，不能混入另一批检索结果。
            allowed.removeIf(j->!referenced.contains(j.getId().toString())||!Objects.equals(j.getVersion(),historicalVersions.get(j.getId().toString())));
        }
        ArrayNode sources=b.json.createArrayNode(); Map<String,ObjectNode> byId=new HashMap<>();
        for(Job old:allowed) {
            Job fresh=jobs.selectById(old.getId());
            if(!available(fresh)||!fresh.getVersion().equals(old.getVersion())) continue;
            ObjectNode item=source(fresh); sources.add(item); byId.put(fresh.getId().toString(),item);
            if(sources.size()==6) break;
        }
        if(sources.isEmpty()&&history.isEmpty()) return b.object("answer","当前没有检索到符合你要求的在招职位，可尝试放宽条件后再问。","sources",List.of());
        JsonNode generated=python.call(HttpMethod.POST,"/internal/ai/recommendation-answer",b.object("question",question,"resumeText",resumeText,"jobs",sources,"history",history));
        if(generated==null||!generated.path("answer").isTextual()||generated.path("answer").asText().isBlank()||generated.path("answer").asText().length()>8000||!generated.path("recommendations").isArray()||generated.path("recommendations").size()>6) PythonAiClient.invalid();
        ArrayNode citations=b.json.createArrayNode(); Set<String> seen=new HashSet<>();
        for(JsonNode rec:generated.path("recommendations")) {
            String id=rec.path("jobId").asText();
            if(!byId.containsKey(id)||!seen.add(id)||!rec.path("reason").isTextual()||rec.path("reason").asText().isBlank()||rec.path("reason").asText().length()>1500) PythonAiClient.invalid();
            ObjectNode item=byId.get(id).deepCopy(); item.remove(List.of("description","requirements","skills"));
            item.put("reason",rec.path("reason").asText()); citations.add(item);
        }
        return b.object("answer",generated.path("answer"),"sources",citations);
    }
    /** 生成后职位可能下架；查询历史回答时再次标记引用是否仍可查看。 */
    public void refreshSources(JsonNode result) {
        if(result==null||!result.path("sources").isArray()) return;
        for(JsonNode source:result.path("sources")) {
            Job job=jobs.selectById(source.path("jobId").asText());
            ((ObjectNode)source).put("available",available(job)&&job.getVersion()==source.path("jobVersion").asInt());
        }
    }
}
