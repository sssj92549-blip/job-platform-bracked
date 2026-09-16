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

/**
 * 简历驱动推荐和RAG共享检索；不读取其他求职者简历，不生成或写入人岗匹配分。
 */
@Service
public class JobRecommendationService {
    private final ResumeMapper resumes;
    private final JobMapper jobs;
    private final ProfileRepository profiles;
    private final JobVectorService vectors;
    private final PythonAiClient python;
    private final BusinessSupport b;

    public JobRecommendationService(ResumeMapper resumes, JobMapper jobs, ProfileRepository profiles, JobVectorService vectors, PythonAiClient python, BusinessSupport b) {
        this.resumes = resumes;
        this.jobs = jobs;
        this.profiles = profiles;
        this.vectors = vectors;
        this.python = python;
        this.b = b;
    }

    /**
     * 只索引已确认的能力信息，不把姓名、电话或年龄作为推荐依据。
     */
    public String context(JsonNode confirmed) {
        StringBuilder text = new StringBuilder("学历：").append(confirmed.path("education").asText()).append("\n技能：").append(confirmed.path("skills"));
        if (confirmed.hasNonNull("workExperienceYears"))
            text.append("\n工作年限：").append(confirmed.path("workExperienceYears").asInt());
        for (String field : List.of("parsedWorkExperience", "parsedInternshipExperience", "parsedProjectExperience", "parsedCampusExperience", "parsedCertificates")) {
            JsonNode value = confirmed.path("optionalSections").path(field);
            if (value.isArray()) {
                String section = value.toString();
                text.append("\n").append(field).append(":").append(section.substring(0, Math.min(4000, section.length())));
            }
        }
        return text.toString();
    }

    public String currentContext(HttpServletRequest request) {
        Profile p = b.actor(request, "JOB_SEEKER");
        Resume r = resumes.selectOne(new QueryWrapper<Resume>().eq("candidate_id", p.getId()).eq("is_current", true));
        if (r == null || !"SUCCESS".equals(r.getParseStatus()) || !"CONFIRMED".equals(r.getConfirmationStatus()))
            state("请先上传并确认简历，再查看推荐职位");
        return context(b.read(r.getConfirmedProfile()));
    }

    public Map<Long, Double> rank(String resumeText, List<Job> allowed) {
        return vectors.recommend("根据我的专业技能和经历推荐合适岗位", resumeText, allowed);
    }

    private boolean available(Job j) {
        return j != null && "APPROVED".equals(j.getStatus()) && b.available(profiles.selectById(j.getCompanyId()));
    }

    private ObjectNode source(Job j) {
        Profile company = profiles.selectById(j.getCompanyId());
        return b.object("jobId", j.getId().toString(), "jobVersion", j.getVersion(), "title", j.getTitle(), "companyName", company.getCompanyName(), "city", j.getCity(), "description", j.getDescription(), "requirements", j.getRequirements(), "skills", b.read(j.getSkills()), "salaryMin", j.getSalaryMin(), "salaryMax", j.getSalaryMax(), "experienceMinYears", j.getExperienceMinYears(), "educationRequirement", j.getEducationRequirement(), "industry", company.getIndustry(), "companySize", company.getCompanySize());
    }

    /**
     * 通用计划先确定意图和条件，再检索、审核候选，最后生成有据可查的回答。
     */
    public ObjectNode answer(String question, String resumeText) {
        return answer(question, resumeText, b.json.createArrayNode());
    }

    public ObjectNode answer(String question, String resumeText, JsonNode history) {
        return answer(question, resumeText, history, b.json.nullNode());
    }

    public ObjectNode answer(String question, String resumeText, JsonNode savedHistory, JsonNode previousPlan) {
        JsonNode history = savedHistory.isArray() ? savedHistory : b.json.createArrayNode();
        ObjectNode planning = b.object("question", question, "history", history);
        if (previousPlan.isObject()) planning.set("previousPlan", previousPlan);
        JsonNode plan = python.call(HttpMethod.POST, "/internal/ai/conversation-query", planning);
        validatePlan(plan);
        String intent = plan.path("intent").asText(), resumeMode = plan.path("resumeMode").asText();
        Map<String, Integer> historicalVersions = new HashMap<>();
        for (JsonNode turn : history)
            for (JsonNode item : turn.path("sources"))
                historicalVersions.put(item.path("jobId").asText(), item.path("jobVersion").asInt());
        Set<String> referenced = new LinkedHashSet<>();
        for (JsonNode id : plan.path("referencedJobIds"))
            if (!id.isTextual() || !historicalVersions.containsKey(id.asText()) || !referenced.add(id.asText()))
                PythonAiClient.invalid();
        if (!"REFERENCES".equals(intent) && !referenced.isEmpty()) PythonAiClient.invalid();
        List<Job> allowed = new ArrayList<>();
        if (!"ADVICE".equals(intent)) {
            allowed.addAll(jobs.selectList(new QueryWrapper<Job>().eq("status", "APPROVED").orderByDesc("id").last("LIMIT 10001")));
            if (allowed.size() > 10000) state("职位库超过单次检索范围，请缩小条件后再试");
            allowed.removeIf(j -> !available(j));
            if ("REFERENCES".equals(intent)) {
                allowed.removeIf(j -> !referenced.contains(j.getId().toString()) || !Objects.equals(j.getVersion(), historicalVersions.get(j.getId().toString())));
                List<String> order = new ArrayList<>(referenced);
                allowed.sort(Comparator.comparingInt(j -> order.indexOf(j.getId().toString())));
            } else {
                if (plan.path("excludeSeen").asBoolean())
                    allowed.removeIf(j -> historicalVersions.containsKey(j.getId().toString()));
                Map<Long, Double> scores = vectors.conversationRank(plan.path("query").asText(), "MATCH".equals(resumeMode) ? resumeText : null, allowed);
                // 全量合法岗位都保留为待审核候选，未入索引的岗位排在末尾，不以简历相似度做硬筛选。
                allowed.sort(Comparator.<Job>comparingDouble(j -> scores.getOrDefault(j.getId(), -1.0)).reversed().thenComparing(Job::getId));
            }
        }
        ArrayNode sources = b.json.createArrayNode();
        Map<String, ObjectNode> byId = new HashMap<>();
        int cursor = 0;
        long reviewDeadline = System.nanoTime() + 90_000_000_000L;
        while (cursor < allowed.size() && sources.size() < 6 && System.nanoTime() < reviewDeadline) {
            ArrayNode batch = b.json.createArrayNode();
            int chars = 0;
            // 批次限制是模型输入预算，不是检索结果上限；不合格候选不会占用回答名额。
            while (cursor < allowed.size() && batch.size() < 12) {
                Job old = allowed.get(cursor), fresh = jobs.selectById(old.getId());
                if (!available(fresh) || !fresh.getVersion().equals(old.getVersion())) {
                    cursor++;
                    continue;
                }
                ObjectNode item = source(fresh);
                int length = item.toString().length();
                if (!batch.isEmpty() && chars + length > 36000) break;
                batch.add(item);
                chars += length;
                cursor++;
            }
            if (batch.isEmpty()) continue;
            ObjectNode review = b.object("plan", plan, "jobs", batch);
            if (!"IGNORE".equals(resumeMode)) review.put("resumeText", resumeText);
            JsonNode reviewed = python.call(HttpMethod.POST, "/internal/ai/review-job-candidates", review);
            Set<String> eligible = eligibleIds(reviewed, batch);
            for (JsonNode item : batch) {
                if (!eligible.contains(item.path("jobId").asText())) continue;
                Job fresh = jobs.selectById(item.path("jobId").asText());
                if (!available(fresh) || fresh.getVersion() != item.path("jobVersion").asInt()) continue;
                sources.add(item);
                byId.put(item.path("jobId").asText(), (ObjectNode) item);
                if (sources.size() == 6) break;
            }
        }
        ObjectNode report = b.object("scope", intent, "candidateCount", allowed.size(), "reviewedCount", cursor, "complete", cursor == allowed.size());
        if (sources.isEmpty() && !"ADVICE".equals(intent))
            return b.object("answer", "REFERENCES".equals(intent) ? "本轮没有可用于回答的所指岗位：它们可能已变更、下架，或不符合当前条件。可以继续查找其他岗位。" : (cursor < allowed.size() ? "本轮已检查的岗位中暂未找到符合条件的结果，仍有岗位尚未检查。可以细化目标后继续查找。" : "本轮检索未找到符合当前条件的可用岗位。可以调整条件后继续查找。"), "sources", List.of(), "searchPlan", plan, "retrieval", report);
        ObjectNode generation = b.object("question", question, "jobs", sources, "history", history, "plan", plan, "retrieval", report);
        if (!"IGNORE".equals(resumeMode)) generation.put("resumeText", resumeText);
        JsonNode generated = python.call(HttpMethod.POST, "/internal/ai/recommendation-answer", generation);
        if (generated == null || !generated.path("answer").isTextual() || generated.path("answer").asText().isBlank() || generated.path("answer").asText().length() > 8000 || !generated.path("recommendations").isArray() || generated.path("recommendations").size() > 6)
            PythonAiClient.invalid();
        ArrayNode citations = b.json.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode rec : generated.path("recommendations")) {
            String id = rec.path("jobId").asText();
            if (!byId.containsKey(id) || !seen.add(id) || !rec.path("reason").isTextual() || rec.path("reason").asText().isBlank() || rec.path("reason").asText().length() > 1500)
                PythonAiClient.invalid();
            ObjectNode item = byId.get(id).deepCopy();
            item.remove(List.of("description", "requirements", "skills", "educationRequirement", "industry", "companySize"));
            item.put("reason", rec.path("reason").asText());
            citations.add(item);
        }
        return b.object("answer", generated.path("answer"), "sources", citations, "searchPlan", plan, "retrieval", report);
    }

    private void validatePlan(JsonNode plan) {
        if (plan == null || !Set.of("SEARCH", "REFERENCES", "ADVICE").contains(plan.path("intent").asText()) || !Set.of("MATCH", "CONTEXT", "IGNORE").contains(plan.path("resumeMode").asText()) || !plan.path("excludeSeen").isBoolean() || !plan.path("query").isTextual() || plan.path("query").asText().isBlank() || plan.path("query").asText().length() > 2000 || !plan.path("referencedJobIds").isArray() || plan.path("referencedJobIds").size() > 6)
            PythonAiClient.invalid();
        for (String key : List.of("requirements", "exclusions")) {
            if (!plan.path(key).isArray() || plan.path(key).size() > 12) PythonAiClient.invalid();
            for (JsonNode value : plan.path(key))
                if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 300)
                    PythonAiClient.invalid();
        }
    }

    private Set<String> eligibleIds(JsonNode result, ArrayNode batch) {
        if (result == null || !result.path("decisions").isArray() || result.path("decisions").size() != batch.size())
            PythonAiClient.invalid();
        Set<String> expected = new HashSet<>(), seen = new HashSet<>(), eligible = new HashSet<>();
        for (JsonNode job : batch) expected.add(job.path("jobId").asText());
        for (JsonNode decision : result.path("decisions")) {
            String id = decision.path("jobId").asText();
            if (!expected.contains(id) || !seen.add(id) || !decision.path("eligible").isBoolean() || !decision.path("reason").isTextual() || decision.path("reason").asText().isBlank() || decision.path("reason").asText().length() > 300)
                PythonAiClient.invalid();
            if (decision.path("eligible").asBoolean()) eligible.add(id);
        }
        return eligible;
    }

    /**
     * 生成后职位可能下架；查询历史回答时再次标记引用是否仍可查看。
     */
    public void refreshSources(JsonNode result) {
        if (result == null || !result.path("sources").isArray()) return;
        for (JsonNode source : result.path("sources")) {
            Job job = jobs.selectById(source.path("jobId").asText());
            ((ObjectNode) source).put("available", available(job) && job.getVersion() == source.path("jobVersion").asInt());
        }
    }
}
