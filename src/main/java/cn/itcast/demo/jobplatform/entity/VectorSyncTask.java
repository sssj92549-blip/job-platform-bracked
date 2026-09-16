package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * vector_sync_task持久化实体，接口使用独立视图避免泄漏内部字段。
 */
@TableName("vector_sync_task")
public class VectorSyncTask extends BaseEntity {
    /**
     * 简历ID。
     */
    private Long resumeId;

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long value) {
        this.resumeId = value;
    }

    /**
     * 目标版本，旧版本删除不依赖当前行版本。
     */
    private Integer resumeVersion;

    public Integer getResumeVersion() {
        return resumeVersion;
    }

    public void setResumeVersion(Integer value) {
        this.resumeVersion = value;
    }

    /**
     * 向量写入或删除。
     */
    private String operation;

    public String getOperation() {
        return operation;
    }

    public void setOperation(String value) {
        this.operation = value;
    }

    /**
     * 同步状态。
     */
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    /**
     * 固定写入文本及元数据，DELETE可空。
     */
    private String payload;

    public String getPayload() {
        return payload;
    }

    public void setPayload(String value) {
        this.payload = value;
    }

    /**
     * 已尝试次数，服务层限制重试上限。
     */
    private Integer attempts;

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer value) {
        this.attempts = value;
    }

    /**
     * 下次执行时间。
     */
    private LocalDateTime nextAttemptAt;

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(LocalDateTime value) {
        this.nextAttemptAt = value;
    }

    /**
     * 执行开始时间，用于恢复超时任务。
     */
    private LocalDateTime startedAt;

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime value) {
        this.startedAt = value;
    }

    /**
     * 完成时间。
     */
    private LocalDateTime completedAt;

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime value) {
        this.completedAt = value;
    }

    /**
     * 脱敏错误摘要。
     */
    private String errorMessage;

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String value) {
        this.errorMessage = value;
    }
}
