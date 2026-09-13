package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 真实Redis Lua、锁所有权及缓存失效测试，仅操作随机键，不清库。 */
@EnabledIfSystemProperty(named="auth.redis.tests",matches="true")
class BusinessRedisTests {
    @Test void atomicRateTtlOwnerSafeLockAndVersionedCache() {
        var config=new RedisStandaloneConfiguration("127.0.0.1",6379); config.setDatabase(1);
        var connection=new LettuceConnectionFactory(config); connection.afterPropertiesSet(); connection.start();
        var redis=new StringRedisTemplate(connection); var service=new BusinessRedis(redis);
        String action="test-"+UUID.randomUUID(); Long id=Math.abs(UUID.randomUUID().getMostSignificantBits());
        String rateKey="job-platform:business:rate:"+action+":"+id;
        String lockKey="job-platform:business:lock:"+action;
        try {
            service.limit(id,action,2,60); service.limit(id,action,2,60);
            assertThatThrownBy(()->service.limit(id,action,2,60)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(42902));
            assertThat(redis.getExpire(rateKey)).isBetween(55L,60L);
            String owner=service.lock(action); service.unlock(action,"wrong-owner");
            assertThatThrownBy(()->service.lock(action)).isInstanceOf(BusinessException.class);
            service.unlock(action,owner); String next=service.lock(action); service.unlock(action,next);
            service.cacheJob(id,1,"{\"title\":\"Java\"}"); assertThat(service.cachedJob(id,1)).contains("Java"); assertThat(service.cachedJob(id,2)).isNull();
            service.evictJob(id,1); assertThat(service.cachedJob(id,1)).isNull();
        } finally { redis.delete(rateKey); redis.delete(lockKey); service.evictJob(id,1); connection.destroy(); }
    }
}
