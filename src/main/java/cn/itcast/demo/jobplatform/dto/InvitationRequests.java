package cn.itcast.demo.jobplatform.dto;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public final class InvitationRequests {
    private InvitationRequests() {}
    public record ApplyInvite(@NotNull @Positive Long candidateId, @NotNull @Positive Long resumeId,
                              @NotNull @Min(1) Integer resumeVersion, @Size(max=1000) String message) {}
    public record InterviewInvite(@NotNull OffsetDateTime interviewAt,
                                  @NotBlank @Pattern(regexp="ONLINE|OFFLINE") String interviewMode,
                                  @NotBlank @Size(max=500) String location, @Size(max=1000) String message) {}
    public record Decision(@NotBlank @Pattern(regexp="ACCEPT|REJECT") String action) {}
}
