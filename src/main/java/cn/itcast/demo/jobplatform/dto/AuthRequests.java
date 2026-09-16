package cn.itcast.demo.jobplatform.dto;

import jakarta.validation.constraints.*;

/**
 * 登录与注册输入；密码保持原样，不做trim。
 */
public final class AuthRequests {
    private AuthRequests() {
    }

    public record Register(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{3,31}") String username,
            @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
            @NotBlank @Size(min = 8, max = 32) @Pattern(regexp = "(?s)(?=.*[A-Za-z])(?=.*[0-9]).*") String password,
            @NotBlank @Pattern(regexp = "JOB_SEEKER|COMPANY") String role,
            @Size(max = 100) String companyName,
            @NotBlank @Size(max = 64) String captchaId,
            @NotBlank @Size(max = 10) String captchaCode) {
    }

    public record Login(@NotBlank @Size(max = 32) String loginName,
                        @NotBlank @Size(max = 32) String password,
                        @NotBlank @Size(max = 64) String captchaId,
                        @NotBlank @Size(max = 10) String captchaCode) {
    }

    public record Choose(@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String profileId) {
    }

    public record AddProfile(@NotBlank @Pattern(regexp = "JOB_SEEKER|COMPANY") String role,
                             @Size(max = 100) String companyName) {
    }
}

