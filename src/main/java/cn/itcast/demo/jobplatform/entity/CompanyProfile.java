package cn.itcast.demo.jobplatform.entity;
import com.baomidou.mybatisplus.annotation.*;
/** company_profile持久化实体。公共创建、更新时间统一由profile维护。 */
@TableName("company_profile")
public class CompanyProfile  {
    /** 对应profile.id，一对一关联，沿用身份ID。 */
    @TableId(value="profile_id",type=IdType.INPUT)
    private Long profileId;
    public Long getProfileId() { return profileId; }
    public void setProfileId(Long value) { profileId=value; }
    /** 企业名称。 */
    private String companyName;
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String value) { companyName=value; }
    /** 企业所属行业。 */
    private String industry;
    public String getIndustry() { return industry; }
    public void setIndustry(String value) { industry=value; }
    /** 企业人数规模编码。 */
    private String companySize;
    public String getCompanySize() { return companySize; }
    public void setCompanySize(String value) { companySize=value; }
    /** 所在城市。 */
    private String city;
    public String getCity() { return city; }
    public void setCity(String value) { city=value; }
    /** 企业简介。 */
    private String companyDescription;
    public String getCompanyDescription() { return companyDescription; }
    public void setCompanyDescription(String value) { companyDescription=value; }
    /** 企业认证审核状态。 */
    private String reviewStatus;
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String value) { reviewStatus=value; }
    /** 企业认证审核说明。 */
    private String reviewReason;
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String value) { reviewReason=value; }
}
