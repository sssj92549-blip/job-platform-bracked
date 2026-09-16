package cn.itcast.demo.jobplatform.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 对外响应只暴露必要字段；所有业务ID使用字符串。
 */
public final class AuthViews {
    private AuthViews() {
    }

    public record Captcha(String captchaId, String imageBase64, int expiresIn, String csrfToken) {
    }

    public record Registered(String userId, String reviewStatus) {
    }

    public record ProfileOption(String id, String role, String name, String companyName, String reviewStatus,
                                boolean enabled) {
    }

    public record User(String id, String accountId, String username, String phone, String role,
                       String name, String education, String avatarUrl, String city, String introduction,
                       boolean discoverable,
                       String companyName, String industry, String companyDescription, String reviewStatus,
                       String reviewReason,
                       boolean enabled, OffsetDateTime createdAt, String companySize) {
    }

    public record Session(String stage, User user, List<ProfileOption> profiles, String csrfToken) {
    }
}
