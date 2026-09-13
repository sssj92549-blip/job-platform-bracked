package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实Service、Mapper、BCrypt和H2；只隔离外部Redis，生产代码没有演示账号。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountMapper accounts;
    @Autowired ProfileMapper profiles;
    @MockitoBean LoginGuard guard;
    private MockHttpSession session;
    private String csrf;
    private CaptchaService.Challenge challenge;

    private void captcha() throws Exception {
        var request=get("/api/auth/captcha");
        if(session!=null) request.session(session);
        var result=mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.imageBase64").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,"))).andReturn();
        session=(MockHttpSession)result.getRequest().getSession();
        csrf=json.readTree(result.getResponse().getContentAsString()).at("/data/csrfToken").asText();
        challenge=(CaptchaService.Challenge)session.getAttribute(CaptchaService.KEY);
    }
    private ResultActions send(String path,Map<String,Object> body) throws Exception {
        var req=post("/api"+path).session(session).header("X-CSRF-Token",csrf).contentType("application/json").content(json.writeValueAsBytes(body));
        Object profile=session.getAttribute(SessionSupport.PROFILE);
        if(profile!=null) req.header("X-Profile-Id",profile.toString());
        return mvc.perform(req);
    }
    private Map<String,Object> credentials(String name,String password) {
        return Map.of("loginName",name,"password",password,"captchaId",challenge.id(),"captchaCode",challenge.answer());
    }
    private String register() throws Exception {
        captcha();
        var result=send("/auth/register",Map.of("username","TestUser","phone","13800138000","password","Password123","role","JOB_SEEKER","captchaId",challenge.id(),"captchaCode",challenge.answer())).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).at("/data/userId").asText();
    }
    private void login(String name) throws Exception {
        captcha();
        var result=send("/auth/login",credentials(name,"Password123")).andExpect(status().isOk()).andReturn();
        csrf=json.readTree(result.getResponse().getContentAsString()).at("/data/csrfToken").asText();
    }
    @Test void registrationPersistsHashAndAnnotationTimestamps() throws Exception {
        String id=register(); Profile p=profiles.selectById(id); Account a=accounts.selectById(p.getAccountId());
        assertThat(a.getUsername()).isEqualTo("testuser");
        assertThat(a.getPasswordHash()).startsWith("$2").isNotEqualTo("Password123");
        assertThat(a.getCreatedAt()).isNotNull(); assertThat(p.getUpdatedAt()).isNotNull();
        LocalDateTime created=p.getCreatedAt(); p.setUpdatedAt(LocalDateTime.of(2000,1,1,0,0));
        p.setName("已更新"); profiles.updateById(p);
        Profile updated=profiles.selectById(id);
        assertThat(updated.getCreatedAt()).isEqualTo(created);
        assertThat(updated.getUpdatedAt()).isAfter(LocalDateTime.of(2000,1,1,0,0));
    }
    @Test void bothLoginNamesWorkAndRotateSessionAndCsrf() throws Exception {
        register(); String oldId=session.getId(); String oldToken=csrf; login("TESTUSER");
        assertThat(session.getId()).isNotEqualTo(oldId); assertThat(csrf).isNotEqualTo(oldToken);
        mvc.perform(get("/api/users/me").session(session).header("X-Profile-Id",session.getAttribute(SessionSupport.PROFILE)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("testuser")).andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        send("/auth/logout",Map.of()).andExpect(status().isOk()); session=null;
        login("13800138000");
        assertThat(session.getAttribute(SessionSupport.PROFILE)).isNotNull();
    }
    @Test void rejectsMissingCsrfCaptchaReuseAndInvalidAdmin() throws Exception {
        captcha();
        mvc.perform(post("/api/auth/login").session(session).contentType("application/json").content(json.writeValueAsBytes(credentials("nobody","Password123"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40303));
        send("/auth/login",credentials("nobody","Password123")).andExpect(status().isUnauthorized());
        send("/auth/login",credentials("nobody","Password123")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40002));
        captcha();
        send("/auth/register",Map.of("username","adminuser","phone","13800138001","password","Password123","role","ADMIN","captchaId",challenge.id(),"captchaCode",challenge.answer()))
            .andExpect(status().isBadRequest());
    }
    @Test void duplicateUsernameRollsBackAndCompanyRequiresName() throws Exception {
        register(); captcha();
        send("/auth/register",Map.of("username","testuser","phone","13800138001","password","Password123","role","JOB_SEEKER","captchaId",challenge.id(),"captchaCode",challenge.answer()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40901));
    }
    @Test void multiIdentityRequiresSelectionAndRejectsForeignProfile() throws Exception {
        String seeker=register(); login("testuser");
        var result=send("/auth/profiles",Map.of("role","COMPANY","companyName","测试企业")).andExpect(status().isCreated()).andReturn();
        String company=json.readTree(result.getResponse().getContentAsString()).at("/data/id").asText();
        send("/auth/profiles",Map.of("role","COMPANY","companyName","测试企业")).andExpect(status().isConflict());
        login("13800138000");
        assertThat(session.getAttribute(SessionSupport.PROFILE)).isNull();
        mvc.perform(get("/api/users/me").session(session)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40907));
        mvc.perform(get("/api/auth/session").session(session)).andExpect(jsonPath("$.data.stage").value("SELECT_PROFILE"));
        send("/auth/select-profile",Map.of("profileId","99999")).andExpect(status().isForbidden());
        var selected=send("/auth/select-profile",Map.of("profileId",seeker)).andExpect(status().isOk()).andReturn();
        csrf=json.readTree(selected.getResponse().getContentAsString()).at("/data/csrfToken").asText();
        send("/auth/switch-profile",Map.of("profileId",company)).andExpect(status().isOk()).andExpect(jsonPath("$.data.user.reviewStatus").value("PENDING"));
        mvc.perform(get("/api/users/me").session(session).header("X-Profile-Id",seeker)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40905));
    }
    @Test void disabledIdentityAndExpiredSelectionAreRejected() throws Exception {
        String id=register(); login("testuser");
        Profile p=profiles.selectById(id); p.setEnabled(false); profiles.updateById(p);
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isForbidden());
        p.setEnabled(true); profiles.updateById(p);
        session.removeAttribute(SessionSupport.PROFILE); session.setAttribute("auth.selectionExpires",0L);
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void wrongPhoneAndUsernamePasswordsShareAccountLockKey() throws Exception {
        String id=register(); String key="job-platform:auth:fail:"+profiles.selectById(id).getAccountId();
        for(String name:List.of("testuser","13800138000")) {
            captcha(); send("/auth/login",credentials(name,"Wrong123")).andExpect(status().isUnauthorized());
        }
        verify(guard,times(2)).failed(key);
        doThrow(new cn.itcast.demo.jobplatform.common.BusinessException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,42901,"已锁定")).when(guard).check(key);
        captcha(); send("/auth/login",credentials("testuser","Password123")).andExpect(status().isTooManyRequests());
    }
}
