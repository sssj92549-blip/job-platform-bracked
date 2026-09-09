package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.*;
import cn.itcast.demo.jobplatform.dto.AuthRequests;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.service.*;
import cn.itcast.demo.jobplatform.vo.AuthViews;
import jakarta.servlet.http.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 认证业务编排：验证码、登录锁定、Session和身份切换集中在服务层。 */
@Service
public class AuthService {
    private final AccountService accounts;
    private final CaptchaService captchas;
    private final LoginGuard guard;
    public AuthService(AccountService accounts,CaptchaService captchas,LoginGuard guard) {
        this.accounts=accounts; this.captchas=captchas; this.guard=guard;
    }
    public AuthViews.Captcha captcha(HttpServletRequest request) {
        return captchas.create(request.getSession());
    }
    public AuthViews.Registered register(AuthRequests.Register input,HttpServletRequest request) {
        captchas.verify(request.getSession(),input.captchaId(),input.captchaCode());
        return accounts.register(input);
    }
    public AuthViews.Session login(AuthRequests.Login input,HttpServletRequest request) {
        HttpSession session=request.getSession();
        synchronized(session) {
            captchas.verify(session,input.captchaId(),input.captchaCode());
            Account account=accounts.findLogin(input.loginName());
            String key="job-platform:auth:fail:"+(account==null?"unknown:"+DigestUtils.md5DigestAsHex(input.loginName().trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)):account.getId());
            guard.check(key);
            if(!accounts.matches(account,input.password())) {
                guard.failed(key);
                throw new BusinessException(HttpStatus.UNAUTHORIZED,40102,"手机号、用户名或密码错误");
            }
            if(!Boolean.TRUE.equals(account.getEnabled())) throw new BusinessException(HttpStatus.FORBIDDEN,40301,"账号已停用");
            List<Profile> profiles=accounts.profiles(account.getId());
            if(profiles.stream().noneMatch(p->Boolean.TRUE.equals(p.getEnabled()))) throw new BusinessException(HttpStatus.FORBIDDEN,40301,"暂无可用身份");
            guard.succeeded(key);
            session.setAttribute(SessionSupport.ACCOUNT,account.getId());
            session.removeAttribute(SessionSupport.PROFILE);
            if(profiles.size()==1) {
                session.setAttribute(SessionSupport.PROFILE,profiles.get(0).getId()); session.setMaxInactiveInterval(1800);
            } else {
                session.setMaxInactiveInterval(300);
                session.setAttribute("auth.selectionExpires",System.currentTimeMillis()+300000);
            }
            SessionSupport.rotate(request);
            return snapshot(session);
        }
    }
    public AuthViews.Session session(HttpServletRequest request) { return snapshot(request.getSession()); }
    public AuthViews.Session profiles(HttpServletRequest request) {
        SessionSupport.account(request.getSession(false));
        return snapshot(request.getSession());
    }
    public AuthViews.Session select(AuthRequests.Choose input,HttpServletRequest request) {
        return choose(input,request,true);
    }
    public AuthViews.Session switchProfile(AuthRequests.Choose input,HttpServletRequest request) {
        return choose(input,request,false);
    }
    private AuthViews.Session choose(AuthRequests.Choose input,HttpServletRequest request,boolean selecting) {
        HttpSession session=request.getSession();
        synchronized(session) {
            SessionSupport.checkCsrf(request);
            AuthViews.Session before=snapshot(session);
            if(selecting != "SELECT_PROFILE".equals(before.stage())) throw new BusinessException(HttpStatus.CONFLICT,40902,"当前会话状态不允许此操作");
            Long accountId=SessionSupport.account(session);
            if(!selecting) checkProfileHeader(request,session);
            long id;
            try { id=Long.parseLong(input.profileId()); }
            catch(NumberFormatException e) { throw new BusinessException(HttpStatus.BAD_REQUEST,40001,"身份ID不合法"); }
            accounts.requireProfile(accountId,id);
            session.setAttribute(SessionSupport.PROFILE,id); session.removeAttribute("auth.selectionExpires");
            session.setMaxInactiveInterval(1800); SessionSupport.rotate(request);
            return snapshot(session);
        }
    }
    public AuthViews.ProfileOption add(AuthRequests.AddProfile input,HttpServletRequest request) {
        HttpSession session=request.getSession();
        synchronized(session) {
            SessionSupport.checkCsrf(request); checkProfileHeader(request,session);
            requireUser(request);
            return accounts.add(SessionSupport.account(session),input);
        }
    }
    public void logout(HttpServletRequest request) {
        HttpSession session=request.getSession(false);
        if(session!=null) session.invalidate();
        
    }
    /** 页面刷新恢复最新账号状态；待选择会话不输出User和业务权限。 */
    public AuthViews.Session snapshot(HttpSession session) {
        synchronized(session) {
            Long accountId=(Long)session.getAttribute(SessionSupport.ACCOUNT);
            if(accountId==null) return new AuthViews.Session("ANONYMOUS",null,List.of(),SessionSupport.csrf(session));
            Long profileId=(Long)session.getAttribute(SessionSupport.PROFILE);
            if(profileId==null) {
                Long expiry=(Long)session.getAttribute("auth.selectionExpires");
                if(expiry==null || expiry<System.currentTimeMillis()) {
                    session.invalidate(); throw new BusinessException(HttpStatus.UNAUTHORIZED,40101,"身份选择已过期，请重新登录");
                }
            }
            Account a=accounts.requireAccount(accountId);
            AuthViews.User user=profileId==null?null:accounts.user(a,accounts.requireProfile(accountId,profileId));
            return new AuthViews.Session(user==null?"SELECT_PROFILE":"AUTHENTICATED",user,
                accounts.profiles(accountId).stream().map(accounts::option).toList(),SessionSupport.csrf(session));
        }
    }
    /** 后端业务入口调用此方法获取可信身份，绝不使用请求体role。 */
    public AuthViews.User requireUser(HttpServletRequest request) {
        SessionSupport.account(request.getSession(false));
        AuthViews.User user=snapshot(request.getSession()).user();
        if(user==null) throw new BusinessException(HttpStatus.CONFLICT,40907,"请先选择身份");
        return user;
    }
    /** 旧标签页携带的身份头仅用于拒绝过期请求，不能用于授权。 */
    public static void checkProfileHeader(HttpServletRequest request,HttpSession session) {
        Object current=session.getAttribute(SessionSupport.PROFILE);
        if(current!=null && !current.toString().equals(request.getHeader("X-Profile-Id")))
            throw new BusinessException(HttpStatus.CONFLICT,40905,"身份已变化，请刷新后重试");
    }
}
