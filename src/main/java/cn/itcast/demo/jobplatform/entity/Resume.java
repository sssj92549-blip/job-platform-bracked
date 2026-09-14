package cn.itcast.demo.jobplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** resume持久化实体，接口使用独立视图避免泄漏内部字段。 */
@TableName("resume")
public class Resume extends BaseEntity {
    /** AI提取的完整出生日期，未明确提供时为空。 */
    private java.time.LocalDate parsedBirthDate;
    public java.time.LocalDate getParsedBirthDate() { return parsedBirthDate; }
    public void setParsedBirthDate(java.time.LocalDate value) { this.parsedBirthDate = value; }
    /** AI提取的明确年龄，仅用于没有完整出生日期的简历。 */
    private Integer parsedAge;
    public Integer getParsedAge() { return parsedAge; }
    public void setParsedAge(Integer value) { this.parsedAge = value; }
    /** AI提取的整年工作年限，不含实习，无法确定时为空。 */
    private Integer parsedWorkExperienceYears;
    public Integer getParsedWorkExperienceYears() { return parsedWorkExperienceYears; }
    public void setParsedWorkExperienceYears(Integer value) { this.parsedWorkExperienceYears = value; }

    /** 所属求职者profile.id。 */
    private Long candidateId;
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long value) { this.candidateId = value; }

    /** 重新解析/确认递增；异步写回必须匹配版本。 */
    private Integer version;
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { this.version = value; }

    /** 是否当前简历。 */
    private Boolean isCurrent;
    public Boolean getIsCurrent() { return isCurrent; }
    public void setIsCurrent(Boolean value) { this.isCurrent = value; }

    /** 逻辑删除，不删除历史投递附件。 */
    private Boolean deleted;
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean value) { this.deleted = value; }

    /** 原始展示文件名。 */
    private String fileName;
    public String getFileName() { return fileName; }
    public void setFileName(String value) { this.fileName = value; }

    /** uploads下随机PDF相对路径。 */
    private String filePath;
    public String getFilePath() { return filePath; }
    public void setFilePath(String value) { this.filePath = value; }

    /** 附件字节数。 */
    private Long fileSize;
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long value) { this.fileSize = value; }

    /** 解析状态。 */
    private String parseStatus;
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String value) { this.parseStatus = value; }

    /** 确认状态。 */
    private String confirmationStatus;
    public String getConfirmationStatus() { return confirmationStatus; }
    public void setConfirmationStatus(String value) { this.confirmationStatus = value; }

    /** 冲突状态。 */
    private String conflictStatus;
    public String getConflictStatus() { return conflictStatus; }
    public void setConflictStatus(String value) { this.conflictStatus = value; }

    /** 当前版本向量状态。 */
    private String indexStatus;
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String value) { this.indexStatus = value; }

    /** AI姓名。 */
    private String parsedName;
    public String getParsedName() { return parsedName; }
    public void setParsedName(String value) { this.parsedName = value; }

    /** AI联系方式，不修改登录手机号。 */
    private String parsedPhone;
    public String getParsedPhone() { return parsedPhone; }
    public void setParsedPhone(String value) { this.parsedPhone = value; }

    /** AI学历。 */
    private String parsedEducation;
    public String getParsedEducation() { return parsedEducation; }
    public void setParsedEducation(String value) { this.parsedEducation = value; }

    /** AI技能数组。 */
    private String parsedSkills;
    public String getParsedSkills() { return parsedSkills; }
    public void setParsedSkills(String value) { this.parsedSkills = value; }

    /** AI工作经历，JSON字符串数组；无内容为NULL。 */
    private String parsedWorkExperience;
    public String getParsedWorkExperience() { return parsedWorkExperience; }
    public void setParsedWorkExperience(String value) { this.parsedWorkExperience = value; }

    /** AI实习经历，JSON字符串数组；无内容为NULL。 */
    private String parsedInternshipExperience;
    public String getParsedInternshipExperience() { return parsedInternshipExperience; }
    public void setParsedInternshipExperience(String value) { this.parsedInternshipExperience = value; }

    /** AI项目经历，JSON字符串数组；无内容为NULL。 */
    private String parsedProjectExperience;
    public String getParsedProjectExperience() { return parsedProjectExperience; }
    public void setParsedProjectExperience(String value) { this.parsedProjectExperience = value; }

    /** AI校园经历，JSON字符串数组；无内容为NULL。 */
    private String parsedCampusExperience;
    public String getParsedCampusExperience() { return parsedCampusExperience; }
    public void setParsedCampusExperience(String value) { this.parsedCampusExperience = value; }

    /** AI证书，JSON字符串数组；无内容为NULL。 */
    private String parsedCertificates;
    public String getParsedCertificates() { return parsedCertificates; }
    public void setParsedCertificates(String value) { this.parsedCertificates = value; }

    /** AI摘要。 */
    private String parsedSummary;
    public String getParsedSummary() { return parsedSummary; }
    public void setParsedSummary(String value) { this.parsedSummary = value; }

    /** 提取全文，服务层限制60000字。 */
    private String extractedText;
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String value) { this.extractedText = value; }

    /** 提取方式。 */
    private String extractionMethod;
    public String getExtractionMethod() { return extractionMethod; }
    public void setExtractionMethod(String value) { this.extractionMethod = value; }

    /** PDF页数。 */
    private Integer pageCount;
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer value) { this.pageCount = value; }

    /** 上传时用户自填资料快照。 */
    private String originalProfile;
    public String getOriginalProfile() { return originalProfile; }
    public void setOriginalProfile(String value) { this.originalProfile = value; }

    /** 差异数组，保留原始证据。 */
    private String conflicts;
    public String getConflicts() { return conflicts; }
    public void setConflicts(String value) { this.conflicts = value; }

    /** 确认后的姓名、联系方式、学历和技能。 */
    private String confirmedProfile;
    public String getConfirmedProfile() { return confirmedProfile; }
    public void setConfirmedProfile(String value) { this.confirmedProfile = value; }

    /** 解析错误摘要。 */
    private String parseError;
    public String getParseError() { return parseError; }
    public void setParseError(String value) { this.parseError = value; }

    /** 向量索引错误摘要。 */
    private String indexError;
    public String getIndexError() { return indexError; }
    public void setIndexError(String value) { this.indexError = value; }

    /** 处理起点，重启恢复使用。 */
    private LocalDateTime parseStartedAt;
    public LocalDateTime getParseStartedAt() { return parseStartedAt; }
    public void setParseStartedAt(LocalDateTime value) { this.parseStartedAt = value; }

    /** 用户确认时间。 */
    private LocalDateTime confirmedAt;
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime value) { this.confirmedAt = value; }
}
