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
        if (result == null || !result.path("matches").isArray()) PythonAiClient.invalid();
        jobs.publicJob(id);
        ArrayNode candidates = b.json.createArrayNode();
        Map<String, ObjectNode> facts = new HashMap<>();
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
            facts.put(resumeId, candidateFacts(confirmed));
            if (candidates.size() >= top) break;
        }
        if (!candidates.isEmpty()) enrich(job, candidates, facts);
        // LLM调用期间可能撤回发现授权、更新简历、投递或下架职位，返回前再次复核。
        b.actor(request, "COMPANY");
        Job latest = jobs.publicJob(id);
        if (!Objects.equals(latest.getVersion(), job.getVersion())) state("职位已更新，请重新检索人才");
        ArrayNode visible = b.json.createArrayNode();
        for (JsonNode candidate : candidates) {
            Resume current = resumes.selectOne(eligible(id).eq("id", candidate.path("resumeId").asText()).eq("version", candidate.path("resumeVersion").asInt()));
            if (current != null) visible.add(candidate);
        }
        return b.object("jobId", id.toString(), "candidates", visible, "returnedCount", visible.size());
    }
    /** 仅从确认快照抽取职业事实，不发送姓名、电话、原始简历全文或未确认摘要。 */
    private ObjectNode candidateFacts(JsonNode confirmed) {
        ObjectNode facts = b.json.createObjectNode();
        for (String field : List.of("education", "skills", "workExperienceYears")) {
            JsonNode value = confirmed.path(field);
            if (!value.isMissingNode() && !value.isNull()) addFact(facts, field, value.isValueNode() ? value.asText() : value.toString());
        }
        for (String field : List.of("parsedWorkExperience", "parsedInternshipExperience", "parsedProjectExperience", "parsedCampusExperience", "parsedCertificates")) {
            JsonNode entries = confirmed.path("optionalSections").path(field);
            if (entries.isArray()) for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).isTextual()) addFact(facts, field + "." + i, entries.get(i).asText());
            }
        }
        return facts;
    }
    private void addFact(ObjectNode facts, String key, String text) {
        int remaining = 6000 - facts.toString().length();
        if (!text.isBlank() && remaining > key.length() + 10) facts.put(key, text.substring(0, Math.min(text.length(), Math.min(2000, remaining - key.length() - 10))));
    }
    private void enrich(Job job, ArrayNode candidates, Map<String, ObjectNode> facts) {
        ObjectNode jobFacts = b.object("title", job.getTitle(), "description", job.getDescription(), "requirements", job.getRequirements());
        if (job.getSkills() != null && !job.getSkills().isBlank()) jobFacts.put("skills", job.getSkills());
        if (job.getEducationRequirement() != null) jobFacts.put("education", job.getEducationRequirement());
        jobFacts.put("experienceMinYears", String.valueOf(job.getExperienceMinYears()));
        for (int start = 0; start < candidates.size(); start += 10) {
            ArrayNode batch = b.json.createArrayNode(); Map<String, ObjectNode> targets = new LinkedHashMap<>();
            for (int i = start; i < Math.min(start + 10, candidates.size()); i++) {
                ObjectNode candidate = (ObjectNode) candidates.get(i); String id = candidate.path("resumeId").asText();
                batch.add(b.object("resumeId", id, "resumeVersion", candidate.path("resumeVersion"), "facts", facts.get(id)));
                targets.put(id, candidate);
            }
            JsonNode generated = python.call(HttpMethod.POST, "/internal/ai/talent-reasons", b.object("jobFacts", jobFacts, "candidates", batch));
            if (generated == null || !generated.path("candidates").isArray() || generated.path("candidates").size() != batch.size()) PythonAiClient.invalid();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : generated.path("candidates")) {
                String id = item.path("resumeId").asText(); ObjectNode target = targets.get(id);
                if (target == null || !seen.add(id) || !item.path("resumeVersion").isIntegralNumber() || item.path("resumeVersion").asInt() != target.path("resumeVersion").asInt()) PythonAiClient.invalid();
                if (!item.path("reason").isTextual() || item.path("reason").asText().isBlank() || item.path("reason").asText().length() > 400 || !item.path("evidence").isArray() || item.path("evidence").size() > 3) PythonAiClient.invalid();
                for (JsonNode evidence : item.path("evidence")) {
                    verifyQuote(facts.get(id), evidence, "candidateField", "candidateQuote");
                    verifyQuote(jobFacts, evidence, "jobField", "jobQuote");
                }
                target.put("reason", item.path("evidence").isEmpty() ? "已通过向量检索召回，但当前已确认资料不足以给出有据可查的匹配原因，需进一步了解。" : item.path("reason").asText());
                target.set("reasonEvidence", item.path("evidence"));
            }
        }
    }
    private void verifyQuote(ObjectNode facts, JsonNode evidence, String fieldName, String quoteName) {
        JsonNode field = evidence.path(fieldName), quote = evidence.path(quoteName);
        if (!field.isTextual() || !quote.isTextual() || quote.asText().isBlank() || quote.asText().length() > 160 || !facts.path(field.asText()).isTextual() || !facts.path(field.asText()).asText().contains(quote.asText())) PythonAiClient.invalid();
    }

}
