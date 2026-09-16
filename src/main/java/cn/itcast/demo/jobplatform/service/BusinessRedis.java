package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.*;

/**
 * Redis只保存可过期的缓存、限流计数和短期提交锁；业务最终状态以MySQL为准。
 */
@Service
public class BusinessRedis {
    private final StringRedisTemplate redis;
    private static final String PREFIX = "job-platform:business:";
    private static final DefaultRedisScript<Long> RATE = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n", Long.class);
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]); end; return 0", Long.class);

    public BusinessRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 原子计数加过期，避免INCR和EXPIRE分离产生永久限流键。
     */
    public void limit(Long profileId, String action, int max, int seconds) {
        Long count = redis.execute(RATE, List.of(PREFIX + "rate:" + action + ":" + profileId), String.valueOf(seconds));
        if (count == null || count > max)
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, 42902, "操作过于频繁，请稍后再试");
    }

    /**
     * 短期互斥只减少重复请求；数据库唯一约束/行锁仍保证最终一致性。
     */
    public String lock(String key) {
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + "lock:" + key, token, Duration.ofSeconds(30))))
            throw new BusinessException(HttpStatus.CONFLICT, 40902, "请求正在处理，请勿重复提交");
        return token;
    }

    public void unlock(String key, String token) {
        try {
            redis.execute(UNLOCK, List.of(PREFIX + "lock:" + key), token);
        } catch (DataAccessException ignored) { /* TTL自动释放。 */ }
    }

    /**
     * 非关键缓存故障回源；鉴权与限流不使用此降级逻辑。
     */
    public String cachedJob(Long id, Integer version) {
        try {
            return redis.opsForValue().get(PREFIX + "job:" + id + ":" + version);
        } catch (DataAccessException e) {
            return null;
        }
    }

    public void cacheJob(Long id, Integer version, String json) {
        try {
            redis.opsForValue().set(PREFIX + "job:" + id + ":" + version, json, Duration.ofMinutes(2));
        } catch (DataAccessException ignored) {
        }
    }

    public void evictJob(Long id, Integer version) {
        try {
            redis.delete(PREFIX + "job:" + id + ":" + version);
        } catch (DataAccessException ignored) {
        }
    }
}
