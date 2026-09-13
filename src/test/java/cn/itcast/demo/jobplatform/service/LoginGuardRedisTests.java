package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 显式启用的真实Redis验证，只操作随机测试键，绝不清空Redis库。 */
@EnabledIfSystemProperty(named="auth.redis.tests",matches="true")
class LoginGuardRedisTests {
    @Test void fifthFailureLocksForFifteenMinutesAndSuccessResetsCounter() {
        RedisStandaloneConfiguration config=new RedisStandaloneConfiguration("127.0.0.1",6379);
        config.setDatabase(1);
        LettuceConnectionFactory connection=new LettuceConnectionFactory(config);
        connection.afterPropertiesSet(); connection.start();
        StringRedisTemplate redis=new StringRedisTemplate(connection);
        String key="job-platform:auth:fail:test:"+UUID.randomUUID();
        LoginGuard guard=new LoginGuard(redis);
        try {
            for(int i=0;i<4;i++) { guard.check(key); guard.failed(key); }
            assertThatThrownBy(()->guard.failed(key)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(42901));
            assertThat(redis.getExpire(key)).isBetween(890L,900L);
            assertThatThrownBy(()->guard.check(key)).isInstanceOf(BusinessException.class);
            // 模拟锁定期结束，避免测试等待15分钟。
            redis.delete(key); guard.check(key);
            guard.failed(key); guard.succeeded(key);
            assertThat(redis.hasKey(key)).isFalse();
        } finally { redis.delete(key); connection.destroy(); }
    }
}
