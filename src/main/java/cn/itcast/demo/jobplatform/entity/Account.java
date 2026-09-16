package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 登录凭证，与角色档案分离。
 */
@TableName("account")
public class Account extends BaseEntity {
    /**
     * 登录手机号。
     */
    private String phone;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String value) {
        this.phone = value;
    }

    /**
     * 规范化用户名。
     */
    private String username;

    public String getUsername() {
        return username;
    }

    public void setUsername(String value) {
        this.username = value;
    }

    /**
     * BCrypt密码哈希，禁止直接返回实体。
     */
    private String passwordHash;

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String value) {
        this.passwordHash = value;
    }

    /**
     * 账号整体启用状态。
     */
    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean value) {
        this.enabled = value;
    }
}

