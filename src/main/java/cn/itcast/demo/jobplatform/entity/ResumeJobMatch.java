package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** resume_job_match持久化实体，接口使用独立视图避免泄漏内部字段。 */
@TableName("resume_job_match")
public class ResumeJobMatch extends CreatedEntity {
    /** 简历ID。 */
    private Long resumeId;
    public Long getResumeId() { return resumeId; }
    public void setResumeId(Long value) { this.resumeId = value; }

    /** 简历版本。 */
    private Integer resumeVersion;
    public Integer getResumeVersion() { return resumeVersion; }
    public void setResumeVersion(Integer value) { this.resumeVersion = value; }

    /** 职位ID。 */
    private Long jobId;
    public Long getJobId() { return jobId; }
    public void setJobId(Long value) { this.jobId = value; }

    /** 职位版本。 */
    private Integer jobVersion;
    public Integer getJobVersion() { return jobVersion; }
    public void setJobVersion(Integer value) { this.jobVersion = value; }

    /** 大模型评分0到100，不是向量相似度。 */
    private Integer score;
    public Integer getScore() { return score; }
    public void setScore(Integer value) { this.score = value; }

    /** 匹配原因数组。 */
    private String reasons;
    public String getReasons() { return reasons; }
    public void setReasons(String value) { this.reasons = value; }

    /** 差距数组。 */
    private String gaps;
    public String getGaps() { return gaps; }
    public void setGaps(String value) { this.gaps = value; }
}
