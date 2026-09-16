package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * application持久化实体，接口使用独立视图避免泄漏内部字段。
 */
@TableName("application")
public class Application extends BaseEntity {
    /**
     * 求职者profile.id。
     */
    private Long candidateId;

    public Long getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(Long value) {
        this.candidateId = value;
    }

    /**
     * 职位ID。
     */
    private Long jobId;

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long value) {
        this.jobId = value;
    }

    /**
     * 投递简历ID，文件保留。
     */
    private Long resumeId;

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long value) {
        this.resumeId = value;
    }

    /**
     * 投递时简历版本。
     */
    private Integer resumeVersion;

    public Integer getResumeVersion() {
        return resumeVersion;
    }

    public void setResumeVersion(Integer value) {
        this.resumeVersion = value;
    }

    /**
     * 投递时职位版本。
     */
    private Integer jobVersion;

    public Integer getJobVersion() {
        return jobVersion;
    }

    public void setJobVersion(Integer value) {
        this.jobVersion = value;
    }

    /**
     * 投递状态。
     */
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    /**
     * 本投递展示资料来源。
     */
    private String profileSource;

    public String getProfileSource() {
        return profileSource;
    }

    public void setProfileSource(String value) {
        this.profileSource = value;
    }

    /**
     * 不可变简历快照，含原填、AI、确认、冲突、全文。
     */
    private String resumeSnapshot;

    public String getResumeSnapshot() {
        return resumeSnapshot;
    }

    public void setResumeSnapshot(String value) {
        this.resumeSnapshot = value;
    }

    /**
     * 不可变职位及公司展示快照。
     */
    private String jobSnapshot;

    public String getJobSnapshot() {
        return jobSnapshot;
    }

    public void setJobSnapshot(String value) {
        this.jobSnapshot = value;
    }
}
