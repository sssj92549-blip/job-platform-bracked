package cn.itcast.demo.jobplatform.entity;
import com.baomidou.mybatisplus.annotation.*;
/** candidate_profile持久化实体。公共创建、更新时间统一由profile维护。 */
@TableName("candidate_profile")
public class CandidateProfile  {
    /** 对应profile.id，一对一关联，沿用身份ID。 */
    @TableId(value="profile_id",type=IdType.INPUT)
    private Long profileId;
    public Long getProfileId() { return profileId; }
    public void setProfileId(Long value) { profileId=value; }
    /** 求职者学历。 */
    private String education;
    public String getEducation() { return education; }
    public void setEducation(String value) { education=value; }
    /** 所在城市。 */
    private String city;
    public String getCity() { return city; }
    public void setCity(String value) { city=value; }
    /** 个人简介。 */
    private String introduction;
    public String getIntroduction() { return introduction; }
    public void setIntroduction(String value) { introduction=value; }
    /** 是否允许企业检索该求职者。 */
    private Boolean discoverable;
    public Boolean getDiscoverable() { return discoverable; }
    public void setDiscoverable(Boolean value) { discoverable=value; }
}
