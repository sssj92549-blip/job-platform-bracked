package cn.itcast.demo.jobplatform.interceptor;

import cn.itcast.demo.jobplatform.common.BusinessException;
import cn.itcast.demo.jobplatform.service.AuthService;
import cn.itcast.demo.jobplatform.service.LoginGuard;
import cn.itcast.demo.jobplatform.service.SessionSupport;
import cn.itcast.demo.jobplatform.vo.AuthViews;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Set;

/**
 * 认证拦截器：统一检查CSRF、登录状态、当前身份及角色权限。
 * 资源归属和具体业务规则仍由Service层校验。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final AuthService auth;
    private final LoginGuard guard;

    public AuthInterceptor(AuthService auth, LoginGuard guard) {
        this.auth = auth;
        this.guard = guard;
    }

    /** Controller执行前校验请求；拒绝时抛业务异常，由全局异常处理器返回JSON。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        String method=request.getMethod();
        boolean read=Set.of("GET","HEAD","OPTIONS").contains(method);
        if(path.startsWith("/api/auth/") || path.equals("/api/users/me")) response.setHeader("Cache-Control","no-store");
        if(Set.of("/api/auth/captcha","/api/auth/login","/api/auth/register").contains(path)) guard.rateLimit(request.getRemoteAddr());
        if(!read) SessionSupport.checkCsrf(request);
        if(path.startsWith("/api/auth/")) return true;
        if(read && (path.equals("/api/jobs") || path.matches("/api/jobs/[0-9]+") || path.matches("/api/companies/[0-9]+") || path.matches("/api/users/[0-9]+/avatar"))) return true;
        AuthViews.User user=auth.requireUser(request);
        AuthService.checkProfileHeader(request,request.getSession());
        String required=path.startsWith("/api/admin/")?"ADMIN":path.startsWith("/api/company/")?"COMPANY":path.startsWith("/api/resumes")?"JOB_SEEKER":null;
        if(required!=null && !required.equals(user.role())) throw new BusinessException(HttpStatus.FORBIDDEN,40301,"当前身份无权访问");
        if("COMPANY".equals(user.role()) && !"APPROVED".equals(user.reviewStatus()) && !path.equals("/api/users/me") && !path.startsWith("/api/company/profile") && !(read && path.equals("/api/company/jobs")))
            throw new BusinessException(HttpStatus.FORBIDDEN,40302,"企业审核通过后才能开展业务");
        return true;
    }
}
