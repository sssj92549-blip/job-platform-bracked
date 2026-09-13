package cn.itcast.demo.jobplatform.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
@MapperScan("cn.itcast.demo.jobplatform.mapper")
public class InfrastructureConfig {
    /** 解析、生成、索引各有调度线程，网络等待不阻塞其他类型任务。 */
    @Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3); scheduler.setThreadNamePrefix("persistent-ai-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(30); return scheduler;
    }
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(50L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public RestTemplate aiRestTemplate(RestTemplateBuilder builder,
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.internal-token}") String token,
            @Value("${app.ai.connect-timeout}") Duration connectTimeout,
            @Value("${app.ai.read-timeout}") Duration readTimeout) {
        return builder.rootUri(baseUrl).connectTimeout(connectTimeout).readTimeout(readTimeout)
                .additionalInterceptors((request, body, execution) -> {
                    if (!token.isBlank()) {
                        request.getHeaders().set("X-Internal-Token", token);
                    }
                    String requestId = org.slf4j.MDC.get("requestId");
                    request.getHeaders().set("X-Request-Id", requestId==null?java.util.UUID.randomUUID().toString():requestId);
                    return execution.execute(request, body);
                }).build();
    }

}
