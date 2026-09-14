package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.*;
import cn.itcast.demo.jobplatform.entity.*;
import cn.itcast.demo.jobplatform.mapper.*;
import cn.itcast.demo.jobplatform.vo.AuthViews;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** 统一可信身份、JSON转换和业务异常；不对外序列化登录凭证。 */
@Component
public class BusinessSupport {
    public final ObjectMapper json;
    private final AuthService auth;
    private final ProfileRepository profiles;
    private final AccountMapper accounts;
    public BusinessSupport(ObjectMapper json, AuthService auth, ProfileRepository profiles, AccountMapper accounts) {
        this.json=json; this.auth=auth; this.profiles=profiles; this.accounts=accounts;
    }
    public Profile actor(HttpServletRequest request, String... roles) {
        AuthViews.User user=auth.requireUser(request);
        if(roles.length>0 && !Arrays.asList(roles).contains(user.role())) forbidden();
        return profiles.selectById(user.id());
    }
    /** 即使存在缓存，也必须读取账号及档案最新启用、审核状态。 */
    public boolean available(Profile p) {
        if(p==null || !Boolean.TRUE.equals(p.getEnabled()) || !"APPROVED".equals(p.getReviewStatus())) return false;
        Account a=accounts.selectById(p.getAccountId());
        return a!=null && Boolean.TRUE.equals(a.getEnabled());
    }
    public ObjectNode object(Object... pairs) {
        ObjectNode node=json.createObjectNode();
        for(int i=0;i<pairs.length;i+=2) node.set((String)pairs[i],json.valueToTree(pairs[i+1]));
        return node;
    }
    public JsonNode read(String value) {
        if(value==null) return NullNode.instance;
        try { return json.readTree(value); } catch(Exception e) { throw new IllegalStateException("Invalid stored JSON",e); }
    }
    public String write(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid JSON",e); }
    }
    public ObjectNode view(Object entity, String... remove) {
        ObjectNode node=json.valueToTree(entity);
        node.remove(Arrays.asList(remove));
        List<String> names=new ArrayList<>(); node.fieldNames().forEachRemaining(names::add);
        for(String name:names) {
            JsonNode v=node.get(name);
            if((name.equals("id") || name.endsWith("Id")) && v.isNumber()) node.put(name,v.asText());
            if(name.endsWith("At") && v.isTextual() && !v.asText().endsWith("Z") && !v.asText().contains("+")) node.put(name,v.asText()+"+08:00");
        }
        return node;
    }
    public static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
    public static void bad(String message) { throw new BusinessException(HttpStatus.BAD_REQUEST,40001,message); }
    public static void state(String message) { throw new BusinessException(HttpStatus.CONFLICT,40902,message); }
    public static void missing() { throw new BusinessException(HttpStatus.NOT_FOUND,40401,"资源不存在或不属于当前用户"); }
    public static void forbidden() { throw new BusinessException(HttpStatus.FORBIDDEN,40301,"当前身份无权执行此操作"); }
    public static void version(boolean matches) { if(!matches) throw new BusinessException(HttpStatus.CONFLICT,40904,"简历版本已变化，请重新加载"); }
    public static String trim(String value) { return value==null?null:value.trim(); }
    public static int number(Map<String,String> query,String key,int fallback,int min,int max) {
        try { int n=Integer.parseInt(query.getOrDefault(key,String.valueOf(fallback))); if(n<min||n>max) throw new NumberFormatException(); return n; }
        catch(NumberFormatException e) { bad(key+"超出允许范围"); return 0; }
    }
    public static long page(Map<String,String> query) { return number(query,"page",1,1,100000); }
    public static long size(Map<String,String> query) { return number(query,"size",10,1,50); }
}
