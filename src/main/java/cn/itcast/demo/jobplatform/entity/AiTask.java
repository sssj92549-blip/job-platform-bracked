package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ai_task持久化实体，接口使用独立视图避免泄漏内部字段。 */
@TableName("ai_task")
public class AiTask extends BaseEntity {
    /** 创建者profile.id，结果访问权限依据。 */
    private Long creatorId;
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long value) { this.creatorId = value; }

    /** 生成任务类型，与API一致。 */
    private String type;
    public String getType() { return type; }
    public void setType(String value) { this.type = value; }

    /** 任务状态。 */
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }

    /** 企业分析的投递ID。 */
    private Long applicationId;
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long value) { this.applicationId = value; }

    /** 简历ID。 */
    private Long resumeId;
    public Long getResumeId() { return resumeId; }
    public void setResumeId(Long value) { this.resumeId = value; }

    /** 固定简历版本。 */
    private Integer resumeVersion;
    public Integer getResumeVersion() { return resumeVersion; }
    public void setResumeVersion(Integer value) { this.resumeVersion = value; }

    /** 助手无需职位。 */
    private Long jobId;
    public Long getJobId() { return jobId; }
    public void setJobId(Long value) { this.jobId = value; }

    /** 固定职位版本。 */
    private Integer jobVersion;
    public Integer getJobVersion() { return jobVersion; }
    public void setJobVersion(Integer value) { this.jobVersion = value; }

    /** 助手单次问题，不保存会话历史。 */
    private String question;
    public String getQuestion() { return question; }
    public void setQuestion(String value) { this.question = value; }

    /** 规范化输入SHA256，含创建者/类型/版本/投递/问题。 */
    private String requestKey;
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { this.requestKey = value; }

    /** 调用时固定输入，不在执行时读取可变简历。 */
    private String inputSnapshot;
    public String getInputSnapshot() { return inputSnapshot; }
    public void setInputSnapshot(String value) { this.inputSnapshot = value; }

    /** 结构校验后的结果。 */
    private String result;
    public String getResult() { return result; }
    public void setResult(String value) { this.result = value; }

    /** 业务错误码。 */
    private Integer errorCode;
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer value) { this.errorCode = value; }

    /** 脱敏错误摘要。 */
    private String errorMessage;
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { this.errorMessage = value; }

    /** 开始处理时间，用于超时恢复。 */
    private LocalDateTime startedAt;
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime value) { this.startedAt = value; }

    /** 结束时间。 */
    private LocalDateTime completedAt;
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime value) { this.completedAt = value; }
}
