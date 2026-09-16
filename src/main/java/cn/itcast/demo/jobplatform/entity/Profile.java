package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 身份与角色资料的只读聚合模型，对应profile_details视图；写入由ProfileRepository分发。
 */
@TableName("profile_details")
public class Profile extends BaseEntity {
    /**
     * 企业人数规模；为空表示尚未填写，不推测企业信息。
     */
    private String companySize;

    public String getCompanySize() {
        return companySize;
    }

    public void setCompanySize(String value) {
        companySize = value;
    }

    /**
     * 所属登录账号。
     */
    private Long accountId;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long value) {
        this.accountId = value;
    }

    /**
     * JOB_SEEKER、COMPANY或ADMIN。
     */
    private String role;

    public String getRole() {
        return role;
    }

    public void setRole(String value) {
        this.role = value;
    }

    /**
     * 姓名。
     */
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    /**
     * 学历。
     */
    private String education;

    public String getEducation() {
        return education;
    }

    public void setEducation(String value) {
        this.education = value;
    }

    /**
     * 头像相对路径。
     */
    private String avatarPath;

    public String getAvatarPath() {
        return avatarPath;
    }

    public void setAvatarPath(String value) {
        this.avatarPath = value;
    }

    /**
     * 城市。
     */
    private String city;

    public String getCity() {
        return city;
    }

    public void setCity(String value) {
        this.city = value;
    }

    /**
     * 个人简介。
     */
    private String introduction;

    public String getIntroduction() {
        return introduction;
    }

    public void setIntroduction(String value) {
        this.introduction = value;
    }

    /**
     * 是否允许人才发现。
     */
    private Boolean discoverable;

    public Boolean getDiscoverable() {
        return discoverable;
    }

    public void setDiscoverable(Boolean value) {
        this.discoverable = value;
    }

    /**
     * 公司名称。
     */
    private String companyName;

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String value) {
        this.companyName = value;
    }

    /**
     * 行业。
     */
    private String industry;

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String value) {
        this.industry = value;
    }

    /**
     * 企业简介。
     */
    private String companyDescription;

    public String getCompanyDescription() {
        return companyDescription;
    }

    public void setCompanyDescription(String value) {
        this.companyDescription = value;
    }

    /**
     * 审核状态。
     */
    private String reviewStatus;

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String value) {
        this.reviewStatus = value;
    }

    /**
     * 审核原因。
     */
    private String reviewReason;

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String value) {
        this.reviewReason = value;
    }

    /**
     * 档案启用状态。
     */
    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean value) {
        this.enabled = value;
    }
}
