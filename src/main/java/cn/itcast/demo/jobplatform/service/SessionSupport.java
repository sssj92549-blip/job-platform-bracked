package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.BusinessException;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 只在服务端Session保存已验证账号及选定档案，前端不能直接设置这些属性。
 */
public final class SessionSupport {
    public static final String ACCOUNT = "auth.account", PROFILE = "auth.profile", CSRF = "auth.csrf";
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionSupport() {
    }

    /**
     * 生成不可预测的CSRF或验证码标识。
     */
    public static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 读取当前CSRF，缺失时在同一Session内生成。
     */
    public static String csrf(HttpSession session) {
        synchronized (session) {
            String token = (String) session.getAttribute(CSRF);
            if (token == null) {
                token = token();
                session.setAttribute(CSRF, token);
            }
            return token;
        }
    }

    /**
     * 登录及切换后轮换Session ID和CSRF，防止固定会话。
     */
    public static void rotate(HttpServletRequest request) {
        request.changeSessionId();
        request.getSession().setAttribute(CSRF, token());
    }

    /**
     * 写请求必须提交服务端已发放的CSRF，不能用缺失token自动放行。
     */
    public static void checkCsrf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String expected = session == null ? null : (String) session.getAttribute(CSRF);
        String actual = request.getHeader("X-CSRF-Token");
        if (expected == null || actual == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(HttpStatus.FORBIDDEN, 40303, "请求验证失效，请刷新验证码后重试");
    }

    /**
     * 从可信Session读取账号ID，不创建匿名Session。
     */
    public static Long account(HttpSession session) {
        Long id = session == null ? null : (Long) session.getAttribute(ACCOUNT);
        if (id == null) throw new BusinessException(HttpStatus.UNAUTHORIZED, 40101, "请先登录");
        return id;
    }
}

