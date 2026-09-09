package cn.itcast.demo.jobplatform.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@MapperScan("cn.itcast.demo.jobplatform.mapper")
public class InfrastructureConfig {
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
                    if (requestId != null) request.getHeaders().set("X-Request-Id", requestId);
                    return execution.execute(request, body);
                }).build();
    }

    @Bean("aiExecutor")
    public Executor aiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(task -> {
            java.util.Map<String, String> context = org.slf4j.MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (context != null) org.slf4j.MDC.setContextMap(context);
                    task.run();
                } finally {
                    org.slf4j.MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
