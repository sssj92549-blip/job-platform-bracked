package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Redis原子计数，用户名与手机号登录统一使用account.id作为锁定键。
 */
@Service
public class LoginGuard {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> FAILURE = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==5 then redis.call('EXPIRE',KEYS[1],900); end; return n", Long.class);
    private static final DefaultRedisScript<Long> RATE = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60); end; return n", Long.class);

    public LoginGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 根据实际连接IP限频，不信任客户端伪造的X-Forwarded-For。
     */
    public void rateLimit(String ip) {
        Long n = redis.execute(RATE, List.of("job-platform:auth:rate:" + ip));
        if (n == null || n > 120)
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, 42902, "请求过于频繁，请稍后再试");
    }

    /**
     * 锁定期间不校验密码，避免正确密码绕过锁定。
     */
    public void check(String key) {
        String value = redis.opsForValue().get(key);
        if (value != null && Long.parseLong(value) >= 5) locked(key);
    }

    /**
     * 第五次连续凭证失败开始锁定15分钟；未知账号的计数也有过期时间。
     */
    public void failed(String key) {
        Long count = redis.execute(FAILURE, List.of(key));
        if (key.contains(":unknown:") && count != null && count < 5) redis.expire(key, Duration.ofMinutes(15));
        if (count != null && count >= 5) locked(key);
    }

    /**
     * 成功登录清除连续失败记录，但不删除其他账号的计数。
     */
    public void succeeded(String key) {
        redis.delete(key);
    }

    private void locked(String key) {
        Long ttl = redis.getExpire(key);
        throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, 42901, "登录已锁定，请在" + Math.max(1, ttl == null ? 900 : ttl) + "秒后重试");
    }
}
