package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.dto.RecruitmentRequests.Talent;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/**
 * 只将MySQL实时白名单发送Chroma；结果再次校验，不返回联系方式和附件。
 */
@Service
public class TalentService {
    private final ResumeMapper resumes;
    private final ProfileRepository profiles;
    private final ApplicationMapper applications;
    private final JobService jobs;
    private final BusinessSupport b;
    private final BusinessRedis redis;
    private final PythonAiClient python;

    public TalentService(ResumeMapper resumes, ProfileRepository profiles, ApplicationMapper applications, JobService jobs, BusinessSupport b, BusinessRedis redis, PythonAiClient python) {
        this.resumes = resumes;
        this.profiles = profiles;
        this.applications = applications;
        this.jobs = jobs;
        this.b = b;
        this.redis = redis;
        this.python = python;
    }

    private QueryWrapper<Resume> eligible(Long jobId) {
        return new QueryWrapper<Resume>().eq("is_current", true).eq("parse_status", "SUCCESS").eq("confirmation_status", "CONFIRMED").eq("index_status", "READY")
                .inSql("candidate_id", "select p.id from profile_details p join account a on a.id=p.account_id where p.role='JOB_SEEKER' and p.enabled=1 and a.enabled=1 and p.review_status='APPROVED' and p.discoverable=1")
                .apply("not exists (select 1 from application a where a.candidate_id=resume.candidate_id and a.job_id={0})", jobId);
    }

    public ObjectNode search(Long id, Talent input, HttpServletRequest request) {
        Profile p = b.actor(request, "COMPANY");
        redis.limit(p.getId(), "talent", 10, 60);
        Job job = jobs.own(id, p, false);
        if (!"APPROVED".equals(job.getStatus())) state("仅已发布职位可检索人才");
        List<Resume> allowed = resumes.selectList(eligible(id).orderByAsc("id").last("LIMIT 10001"));
        if (allowed.size() > 10000) state("人才库超过当前单次检索上限，请联系管理员分片检索");
        ArrayNode refs = b.json.createArrayNode();
        Map<String, Integer> versions = new HashMap<>();
        for (Resume r : allowed) {
            refs.add(b.object("resumeId", r.getId().toString(), "resumeVersion", r.getVersion()));
            versions.put(r.getId().toString(), r.getVersion());
        }
        if (allowed.isEmpty()) return b.object("jobId", id.toString(), "candidates", List.of(), "returnedCount", 0);
        int top = input.topK() == null ? 10 : input.topK();
        double min = input.minSimilarity() == null ? 0.6 : input.minSimilarity();
        JsonNode result = python.call(HttpMethod.POST, "/internal/vector/talents/search", b.object("jobId", id.toString(), "jobVersion", job.getVersion(), "queryText", job.getTitle() + "\n" + job.getDescription() + "\n" + job.getRequirements() + "\n" + job.getSkills(), "topK", top, "minSimilarity", min, "eligibleResumes", refs));
        if (!result.path("matches").isArray()) PythonAiClient.invalid();
        jobs.publicJob(id);
        ArrayNode candidates = b.json.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode m : result.path("matches")) {
            String resumeId = m.path("resumeId").asText();
            if (!versions.containsKey(resumeId) || versions.get(resumeId) != m.path("resumeVersion").asInt() || !seen.add(resumeId))
                continue;
            double similarity = m.path("similarity").asDouble(-1);
            if (!Double.isFinite(similarity) || similarity < min || similarity > 1) continue;
            Resume r = resumes.selectOne(eligible(id).eq("id", resumeId).eq("version", versions.get(resumeId)));
            if (r == null) continue;
            JsonNode confirmed = b.read(r.getConfirmedProfile());
            candidates.add(b.object("candidateId", r.getCandidateId().toString(), "resumeId", resumeId, "resumeVersion", r.getVersion(), "name", confirmed.path("name"), "education", confirmed.path("education"), "skills", confirmed.path("skills"), "summary", r.getParsedSummary(), "similarity", similarity));
            if (candidates.size() >= top) break;
        }
        return b.object("jobId", id.toString(), "candidates", candidates, "returnedCount", candidates.size());
    }
}
