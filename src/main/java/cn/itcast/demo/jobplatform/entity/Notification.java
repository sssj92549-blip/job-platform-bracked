package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("notification")
public class Notification extends BaseEntity {
    private Long recipientId;
    public Long getRecipientId() { return recipientId; }
    public void setRecipientId(Long value) { recipientId = value; }
    private Long invitationId;
    public Long getInvitationId() { return invitationId; }
    public void setInvitationId(Long value) { invitationId = value; }
    private String title;
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    private String body;
    public String getBody() { return body; }
    public void setBody(String value) { body = value; }
    private LocalDateTime readAt;
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime value) { readAt = value; }
}
