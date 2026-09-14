package cn.itcast.demo.jobplatform.entity;
import com.baomidou.mybatisplus.annotation.*;
/** profile持久化实体。公共创建、更新时间统一由profile维护。 */
@TableName("profile")
public class IdentityProfile extends BaseEntity {
    /** 所属登录账号ID。 */
    private Long accountId;
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId=value; }
    /** 身份角色。 */
    private String role;
    public String getRole() { return role; }
    public void setRole(String value) { role=value; }
    /** 身份显示名称或联系人姓名。 */
    private String name;
    public String getName() { return name; }
    public void setName(String value) { name=value; }
    /** 头像或企业Logo的相对路径。 */
    private String avatarPath;
    public String getAvatarPath() { return avatarPath; }
    public void setAvatarPath(String value) { avatarPath=value; }
    /** 身份是否启用。 */
    private Boolean enabled;
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled=value; }
}
