package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.BusinessException;
import cn.itcast.demo.jobplatform.dto.AuthRequests;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import cn.itcast.demo.jobplatform.vo.AuthViews;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;

/** 账号与档案持久化服务；事务只管理MySQL，不将密码校验放入长事务。 */
@Service
public class AccountService {
    private final AccountMapper accounts;
    private final ProfileMapper profiles;
    private final PasswordEncoder passwords;
    private final String dummyHash;
    public AccountService(AccountMapper accounts,ProfileMapper profiles,PasswordEncoder passwords) {
        this.accounts=accounts; this.profiles=profiles; this.passwords=passwords;
        this.dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    /** 创建账号和首个档案，要么同时成功，要么同时回滚。 */
    @Transactional
    public AuthViews.Registered register(AuthRequests.Register input) {
        if(input.password().getBytes(StandardCharsets.UTF_8).length>72) bad("密码UTF-8长度不能超过72字节");
        validateRole(input.role(),input.companyName());
        Account account=new Account();
        account.setPhone(input.phone()); account.setUsername(input.username().toLowerCase(Locale.ROOT));
        account.setPasswordHash(passwords.encode(input.password())); account.setEnabled(true);
        try { accounts.insert(account); }
        catch(DuplicateKeyException e) { throw new BusinessException(HttpStatus.CONFLICT,40901,"手机号或用户名已注册"); }
        Profile p=newProfile(account.getId(),input.role(),input.companyName()); profiles.insert(p);
        return new AuthViews.Registered(p.getId().toString(),p.getReviewStatus());
    }
    /** 将两种登录凭证归并到同一账号；用户名规范化后精确查询。 */
    public Account findLogin(String login) {
        String normalized=login.trim().toLowerCase(Locale.ROOT);
        return accounts.selectOne(new QueryWrapper<Account>().eq(normalized.matches("1[3-9][0-9]{9}")?"phone":"username",normalized));
    }
    /** 未知账号也执行BCrypt校验，响应不暴露账号是否存在。 */
    public boolean matches(Account account,String password) {
        if(password.getBytes(StandardCharsets.UTF_8).length>72) return false;
        return passwords.matches(password,account==null?dummyHash:account.getPasswordHash()) && account!=null;
    }
    public Account requireAccount(Long id) {
        Account a=accounts.selectById(id);
        if(a==null || !Boolean.TRUE.equals(a.getEnabled())) throw new BusinessException(HttpStatus.UNAUTHORIZED,40101,"账号已失效，请重新登录");
        return a;
    }
    /** 每次从数据库读取最新状态，禁用后不能继续依赖旧Session权限。 */
    public List<Profile> profiles(Long accountId) {
        return profiles.selectList(new QueryWrapper<Profile>().eq("account_id",accountId).orderByAsc("id"));
    }
    public Profile requireProfile(Long accountId,Long profileId) {
        Profile p=profiles.selectById(profileId);
        if(p==null || !p.getAccountId().equals(accountId) || !Boolean.TRUE.equals(p.getEnabled()))
            throw new BusinessException(HttpStatus.FORBIDDEN,40301,"该身份不可使用");
        return p;
    }
    @Transactional
    public AuthViews.ProfileOption add(Long accountId,AuthRequests.AddProfile input) {
        requireAccount(accountId);
        if(profiles(accountId).stream().anyMatch(p->"ADMIN".equals(p.getRole())))
            throw new BusinessException(HttpStatus.FORBIDDEN,40301,"管理员不可添加业务身份");
        validateRole(input.role(),input.companyName());
        Profile p=newProfile(accountId,input.role(),input.companyName());
        try { profiles.insert(p); }
        catch(DuplicateKeyException e) { throw new BusinessException(HttpStatus.CONFLICT,40906,"已存在该角色身份"); }
        return option(p);
    }
    private Profile newProfile(Long accountId,String role,String companyName) {
        Profile p=new Profile(); p.setAccountId(accountId); p.setRole(role);
        p.setCompanyName("COMPANY".equals(role)?companyName.trim():null);
        p.setReviewStatus("COMPANY".equals(role)?"PENDING":"APPROVED"); p.setEnabled(true); p.setDiscoverable(false);
        return p;
    }
    private void validateRole(String role,String companyName) {
        if(!Set.of("JOB_SEEKER","COMPANY").contains(role)) bad("不允许注册该角色");
        if("COMPANY".equals(role) && (companyName==null || companyName.trim().length()<2 || companyName.trim().length()>100)) bad("公司名称须为2到100字");
    }
    private void bad(String message) { throw new BusinessException(HttpStatus.BAD_REQUEST,40001,message); }
    public AuthViews.ProfileOption option(Profile p) {
        return new AuthViews.ProfileOption(p.getId().toString(),p.getRole(),p.getName(),p.getCompanyName(),p.getReviewStatus(),Boolean.TRUE.equals(p.getEnabled()));
    }
    /** 组装User视图，绝不序列化Account实体中的密码哈希。 */
    public AuthViews.User user(Account a,Profile p) {
        return new AuthViews.User(p.getId().toString(),a.getId().toString(),a.getUsername(),a.getPhone(),p.getRole(),
            p.getName(),p.getEducation(),p.getAvatarPath()==null?null:"/api/users/"+p.getId()+"/avatar",
            p.getCity(),p.getIntroduction(),Boolean.TRUE.equals(p.getDiscoverable()),p.getCompanyName(),p.getIndustry(),
            p.getCompanyDescription(),p.getReviewStatus(),p.getReviewReason(),Boolean.TRUE.equals(a.getEnabled()) && Boolean.TRUE.equals(p.getEnabled()),p.getCreatedAt().atOffset(ZoneOffset.ofHours(8)),p.getCompanySize());
    }
}
