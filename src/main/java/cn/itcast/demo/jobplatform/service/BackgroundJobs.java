package cn.itcast.demo.jobplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

/** 持久化任务轮询器；服务重启后PENDING继续执行，超时PROCESSING转为失败或有限重试。 */
@Component
public class BackgroundJobs {
    private final ResumeService resumes;
    private final AiTaskService ai;
    private final VectorSyncService vectors;
    private final boolean enabled;
    public BackgroundJobs(ResumeService resumes,AiTaskService ai,VectorSyncService vectors,@Value("${app.jobs.enabled:true}") boolean enabled) { this.resumes=resumes; this.ai=ai; this.vectors=vectors; this.enabled=enabled; }
    @Scheduled(fixedDelay=2000,initialDelay=5000)
    public void parse() { run(()->{ resumes.recover(); resumes.processOne(); }); }
    @Scheduled(fixedDelay=2000,initialDelay=6000)
    public void generate() { run(()->{ ai.recover(); ai.processOne(); }); }
    @Scheduled(fixedDelay=2000,initialDelay=7000)
    public void index() { run(()->{ vectors.recover(); vectors.processOne(); }); }
    private void run(Runnable work) {
        if(!enabled) return;
        try { work.run(); } catch(Exception e) { LoggerFactory.getLogger(getClass()).error("Background task failed: {}",e.getClass().getSimpleName()); }
    }
}
