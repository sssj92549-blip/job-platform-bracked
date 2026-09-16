package cn.itcast.demo.jobplatform.controller;

import cn.itcast.demo.jobplatform.common.ApiResponse;
import cn.itcast.demo.jobplatform.dto.InvitationRequests.*;
import cn.itcast.demo.jobplatform.service.InvitationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class InvitationController {
    private final InvitationService service;
    public InvitationController(InvitationService service) { this.service=service; }
    @PostMapping("/api/company/jobs/{id}/application-invitations")
    public ApiResponse<?> apply(@PathVariable Long id,@Valid @RequestBody ApplyInvite input,HttpServletRequest r) { return ApiResponse.success(service.inviteApply(id,input,r)); }
    @PostMapping("/api/company/applications/{id}/interview-invitations")
    public ApiResponse<?> interview(@PathVariable Long id,@Valid @RequestBody InterviewInvite input,HttpServletRequest r) { return ApiResponse.success(service.inviteInterview(id,input,r)); }
    @GetMapping("/api/invitations")
    public ApiResponse<?> list(@RequestParam Map<String,String> q,HttpServletRequest r) { return ApiResponse.success(service.list(q,r)); }
    @GetMapping("/api/invitations/{id}")
    public ApiResponse<?> detail(@PathVariable Long id,HttpServletRequest r) { return ApiResponse.success(service.detail(id,false,r)); }
    @PostMapping("/api/invitations/{id}/view")
    public ApiResponse<?> view(@PathVariable Long id,HttpServletRequest r) { return ApiResponse.success(service.detail(id,true,r)); }
    @PostMapping("/api/invitations/{id}/respond")
    public ApiResponse<?> respond(@PathVariable Long id,@Valid @RequestBody Decision input,HttpServletRequest r) { return ApiResponse.success(service.respond(id,input,r)); }
    @PostMapping("/api/company/invitations/{id}/cancel")
    public ApiResponse<?> cancel(@PathVariable Long id,HttpServletRequest r) { return ApiResponse.success(service.cancel(id,r)); }
    @GetMapping("/api/notifications")
    public ApiResponse<?> notices(@RequestParam Map<String,String> q,HttpServletRequest r) { return ApiResponse.success(service.notifications(q,r)); }
    @GetMapping("/api/notifications/unread-count")
    public ApiResponse<?> count(HttpServletRequest r) { return ApiResponse.success(service.unread(r)); }
    @PostMapping("/api/notifications/read-all")
    public ApiResponse<?> readAll(HttpServletRequest r) { return ApiResponse.success(service.read(null,r)); }
    @PostMapping("/api/notifications/{id}/read")
    public ApiResponse<?> read(@PathVariable Long id,HttpServletRequest r) { return ApiResponse.success(service.read(id,r)); }
}
