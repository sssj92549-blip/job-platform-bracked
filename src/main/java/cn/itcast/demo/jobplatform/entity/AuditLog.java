package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** audit_log持久化实体，接口使用独立视图避免泄漏内部字段。 */
@TableName("audit_log")
public class AuditLog extends CreatedEntity {
    /** 操作者profile.id。 */
    private Long operatorId;
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long value) { this.operatorId = value; }

    /** 操作对象类型。 */
    private String targetType;
    public String getTargetType() { return targetType; }
    public void setTargetType(String value) { this.targetType = value; }

    /** 多态对象ID，由服务层验证。 */
    private Long targetId;
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long value) { this.targetId = value; }

    /** 如APPROVE/REJECT/DISABLE/ENABLE/CLOSE/ADOPT_AI。 */
    private String action;
    public String getAction() { return action; }
    public void setAction(String value) { this.action = value; }

    /** 操作原因。 */
    private String reason;
    public String getReason() { return reason; }
    public void setReason(String value) { this.reason = value; }

    /** 变更前必要字段，禁止密码等敏感信息。 */
    private String beforeValue;
    public String getBeforeValue() { return beforeValue; }
    public void setBeforeValue(String value) { this.beforeValue = value; }

    /** 变更后必要字段。 */
    private String afterValue;
    public String getAfterValue() { return afterValue; }
    public void setAfterValue(String value) { this.afterValue = value; }

    /** 请求追踪ID。 */
    private String requestId;
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { this.requestId = value; }
}
