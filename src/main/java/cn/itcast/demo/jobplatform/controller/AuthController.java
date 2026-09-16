package cn.itcast.demo.jobplatform.controller;

import cn.itcast.demo.jobplatform.common.ApiResponse;
import cn.itcast.demo.jobplatform.dto.AuthRequests;
import cn.itcast.demo.jobplatform.service.AuthService;
import cn.itcast.demo.jobplatform.vo.AuthViews;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 认证HTTP入口：只接收参数、校验、调用服务和包装响应。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/captcha")
    public ApiResponse<AuthViews.Captcha> captcha(HttpServletRequest request) {
        return ApiResponse.success(auth.captcha(request));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AuthViews.Registered> register(@Valid @RequestBody AuthRequests.Register input, HttpServletRequest request) {
        return ApiResponse.success(auth.register(input, request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthViews.Session> login(@Valid @RequestBody AuthRequests.Login input, HttpServletRequest request) {
        return ApiResponse.success(auth.login(input, request));
    }

    @GetMapping("/session")
    public ApiResponse<AuthViews.Session> session(HttpServletRequest request) {
        return ApiResponse.success(auth.session(request));
    }

    @GetMapping("/profiles")
    public ApiResponse<AuthViews.Session> profiles(HttpServletRequest request) {
        return ApiResponse.success(auth.profiles(request));
    }

    @PostMapping("/select-profile")
    public ApiResponse<AuthViews.Session> select(@Valid @RequestBody AuthRequests.Choose input, HttpServletRequest request) {
        return ApiResponse.success(auth.select(input, request));
    }

    @PostMapping("/switch-profile")
    public ApiResponse<AuthViews.Session> switchProfile(@Valid @RequestBody AuthRequests.Choose input, HttpServletRequest request) {
        return ApiResponse.success(auth.switchProfile(input, request));
    }

    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AuthViews.ProfileOption> add(@Valid @RequestBody AuthRequests.AddProfile input, HttpServletRequest request) {
        return ApiResponse.success(auth.add(input, request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        auth.logout(request);
        return ApiResponse.success(null);
    }
}
