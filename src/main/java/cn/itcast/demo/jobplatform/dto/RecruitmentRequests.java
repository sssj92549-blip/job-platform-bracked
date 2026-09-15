package cn.itcast.demo.jobplatform.dto;

import jakarta.validation.constraints.*;
import java.util.List;

/** 招聘业务输入，只列出允许修改的字段，避免实体直接绑定导致越权写入。 */
public final class RecruitmentRequests {
    private RecruitmentRequests() {}
    public static final String EDUCATION="HIGH_SCHOOL|JUNIOR_COLLEGE|BACHELOR|MASTER|DOCTOR|OTHER";
    public record JobInput(@NotBlank @Size(min=2,max=100) String title,
        @NotBlank @Size(max=50) String city, @NotNull @Min(0) @Max(1000000) Integer salaryMin,
        @NotNull @Min(0) @Max(1000000) Integer salaryMax,
        @Pattern(regexp=EDUCATION) String educationRequirement,
        @NotNull @Min(0) @Max(50) Integer experienceMinYears,
        @NotBlank @Size(max=10000) String description, @NotBlank @Size(max=10000) String requirements,
        @NotNull @Size(max=50) List<@NotBlank @Size(max=100) String> skills) {}
    public record Personal(@NotBlank @Size(max=50) String name,
        @NotNull @Pattern(regexp=EDUCATION) String education,
        @Size(max=50) String city, @Size(max=2000) String introduction) {}
    public record Company(@NotBlank @Size(min=2,max=100) String companyName,
        @Size(max=100) String industry, @Size(max=50) String city, @Size(max=2000) String companyDescription,
        @Pattern(regexp="UNDER_20|20_99|100_499|500_999|1000_9999|10000_PLUS") String companySize) {}
    public record Discoverability(@NotNull Boolean discoverable) {}
    public record Version(@NotNull @Min(1) Integer expectedVersion) {}
    public record Confirm(@NotNull @Min(1) Integer expectedVersion,
        @NotBlank @Size(max=50) String name, @NotBlank @Pattern(regexp="1[3-9][0-9]{9}") String contactPhone,
        @NotNull @Pattern(regexp=EDUCATION) String education,
        @NotNull @Size(max=50) List<@NotBlank @Size(max=100) String> skills, java.util.Map<String,List<String>> optionalSections, @PastOrPresent java.time.LocalDate birthDate, @Min(0) @Max(60) Integer workExperienceYears, @Min(0) @Max(120) Integer age) {
        public Confirm(Integer expectedVersion,String name,String contactPhone,String education,List<String> skills,java.util.Map<String,List<String>> optionalSections) { this(expectedVersion,name,contactPhone,education,skills,optionalSections,null,null,null); }
        public Confirm(Integer expectedVersion,String name,String contactPhone,String education,List<String> skills) { this(expectedVersion,name,contactPhone,education,skills,null); }
    }
    public record Apply(@NotNull @Positive Long jobId, @NotNull @Positive Long resumeId,
        @NotNull @Min(1) Integer resumeVersion) {}
    public record ApplicationStatus(@NotNull @Pattern(regexp="VIEWED|SHORTLISTED|REJECTED") String status) {}
    public record Source(@NotNull @Pattern(regexp="AI|CONFIRMED") String profileSource) {}
    public record Review(@NotNull @Pattern(regexp="APPROVED|REJECTED") String decision, @Size(max=500) String reason) {}
    public record Enabled(@NotNull Boolean enabled, @NotBlank @Size(max=500) String reason) {}
    public record Reason(@NotBlank @Size(max=500) String reason) {}
    public record AiInput(@Positive Long applicationId, @Positive Long resumeId,
        @Min(1) Integer resumeVersion, @Positive Long jobId, @Size(min=1,max=2000) String question, @Positive Long previousTaskId) {
        public AiInput(Long applicationId,Long resumeId,Integer resumeVersion,Long jobId,String question) {
            this(applicationId,resumeId,resumeVersion,jobId,question,null);
        }
    }
    public record Talent(@Min(1) @Max(50) Integer topK, @DecimalMin("0") @DecimalMax("1") Double minSimilarity) {}
}
