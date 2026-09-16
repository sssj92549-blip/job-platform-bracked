package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("recruitment_invitation")
public class Invitation extends BaseEntity {
    private String type;
    public String getType() { return type; }
    public void setType(String value) { type = value; }
    private Long companyId;
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long value) { companyId = value; }
    private Long candidateId;
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long value) { candidateId = value; }
    private Long jobId;
    public Long getJobId() { return jobId; }
    public void setJobId(Long value) { jobId = value; }
    private Long applicationId;
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long value) { applicationId = value; }
    private String jobTitle;
    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String value) { jobTitle = value; }
    private String companyName;
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String value) { companyName = value; }
    private String candidateName;
    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String value) { candidateName = value; }
    private String message;
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    private String activeKey;
    public String getActiveKey() { return activeKey; }
    public void setActiveKey(String value) { activeKey = value; }
    private LocalDateTime interviewAt;
    public LocalDateTime getInterviewAt() { return interviewAt; }
    public void setInterviewAt(LocalDateTime value) { interviewAt = value; }
    private String interviewMode;
    public String getInterviewMode() { return interviewMode; }
    public void setInterviewMode(String value) { interviewMode = value; }
    private String location;
    public String getLocation() { return location; }
    public void setLocation(String value) { location = value; }
    private LocalDateTime expiresAt;
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
    private LocalDateTime viewedAt;
    public LocalDateTime getViewedAt() { return viewedAt; }
    public void setViewedAt(LocalDateTime value) { viewedAt = value; }
    private LocalDateTime respondedAt;
    public LocalDateTime getRespondedAt() { return respondedAt; }
    public void setRespondedAt(LocalDateTime value) { respondedAt = value; }
    private String invalidReason;
    public String getInvalidReason() { return invalidReason; }
    public void setInvalidReason(String value) { invalidReason = value; }
}
