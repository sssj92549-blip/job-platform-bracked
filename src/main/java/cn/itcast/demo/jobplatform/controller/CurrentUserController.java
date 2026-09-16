package cn.itcast.demo.jobplatform.controller;

import cn.itcast.demo.jobplatform.common.ApiResponse;
import cn.itcast.demo.jobplatform.service.AuthService;
import cn.itcast.demo.jobplatform.vo.AuthViews;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 当前用户信息接口，返回选定档案而不是登录凭证实体。
 */
@RestController
public class CurrentUserController {
    private final AuthService auth;

    public CurrentUserController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/api/users/me")
    public ApiResponse<AuthViews.User> me(HttpServletRequest request) {
        return ApiResponse.success(auth.requireUser(request));
    }
}
