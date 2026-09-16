package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * job持久化实体，接口使用独立视图避免泄漏内部字段。
 */
@TableName("job")
public class Job extends BaseEntity {
    /**
     * 所属企业profile.id。
     */
    private Long companyId;

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long value) {
        this.companyId = value;
    }

    /**
     * 职位名称。
     */
    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String value) {
        this.title = value;
    }

    /**
     * 工作城市。
     */
    private String city;

    public String getCity() {
        return city;
    }

    public void setCity(String value) {
        this.city = value;
    }

    /**
     * 最低月薪，元。
     */
    private Integer salaryMin;

    public Integer getSalaryMin() {
        return salaryMin;
    }

    public void setSalaryMin(Integer value) {
        this.salaryMin = value;
    }

    /**
     * 最高月薪，元。
     */
    private Integer salaryMax;

    public Integer getSalaryMax() {
        return salaryMax;
    }

    public void setSalaryMax(Integer value) {
        this.salaryMax = value;
    }

    /**
     * 学历要求，NULL不限。
     */
    private String educationRequirement;

    public String getEducationRequirement() {
        return educationRequirement;
    }

    public void setEducationRequirement(String value) {
        this.educationRequirement = value;
    }

    /**
     * 最低经验年限。
     */
    private Integer experienceMinYears;

    public Integer getExperienceMinYears() {
        return experienceMinYears;
    }

    public void setExperienceMinYears(Integer value) {
        this.experienceMinYears = value;
    }

    /**
     * 职位描述，服务层限制10000字。
     */
    private String description;

    public String getDescription() {
        return description;
    }

    public void setDescription(String value) {
        this.description = value;
    }

    /**
     * 职位要求，服务层限制10000字。
     */
    private String requirements;

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String value) {
        this.requirements = value;
    }

    /**
     * 技能字符串数组，允许[]。
     */
    private String skills;

    public String getSkills() {
        return skills;
    }

    public void setSkills(String value) {
        this.skills = value;
    }

    /**
     * 职位状态。
     */
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    /**
     * 草稿修改乐观锁版本。
     */
    private Integer version;

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        this.version = value;
    }

    /**
     * 审核或关闭原因。
     */
    private String reviewReason;

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String value) {
        this.reviewReason = value;
    }

    /**
     * 审核通过发布时间。
     */
    private LocalDateTime publishedAt;

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime value) {
        this.publishedAt = value;
    }

    /**
     * 逻辑删除；仅允许删除草稿等文档指定状态。
     */
    private Boolean deleted;

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean value) {
        this.deleted = value;
    }
}
