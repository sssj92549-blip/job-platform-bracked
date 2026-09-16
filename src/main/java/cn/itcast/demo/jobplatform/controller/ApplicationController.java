package cn.itcast.demo.jobplatform.controller;

import cn.itcast.demo.jobplatform.common.ApiResponse;
import cn.itcast.demo.jobplatform.dto.RecruitmentRequests.*;
import cn.itcast.demo.jobplatform.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * ApplicationController路由适配；业务逻辑统一由ApplicationService处理。
 */
@RestController
public class ApplicationController {
    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @PostMapping("/api/applications")
    public ResponseEntity<?> create(@Valid @RequestBody Apply input, HttpServletRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.success(service.create(input, request)));
    }

    @GetMapping("/api/applications/me")
    public ResponseEntity<?> listMine(@RequestParam Map<String, String> query, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.list(query, false, request)));
    }

    @GetMapping("/api/company/applications")
    public ResponseEntity<?> listCompany(@RequestParam Map<String, String> query, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.list(query, true, request)));
    }

    @GetMapping("/api/applications/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.detail(id, request)));
    }

    @PostMapping("/api/applications/{id}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable Long id, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.change(id, "WITHDRAWN", null, request)));
    }

    @PostMapping("/api/company/applications/{id}/view")
    public ResponseEntity<?> opened(@PathVariable Long id, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.opened(id, request)));
    }

    @PatchMapping("/api/company/applications/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @Valid @RequestBody ApplicationStatus input, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.change(id, input.status(), null, request)));
    }

    @PatchMapping("/api/company/applications/{id}/profile-source")
    public ResponseEntity<?> updateProfileSource(@PathVariable Long id, @Valid @RequestBody Source input, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.change(id, null, input.profileSource(), request)));
    }

    @GetMapping("/api/applications/{id}/resume-file")
    public ResponseEntity<Resource> downloadResume(@PathVariable Long id, HttpServletRequest request) {
        return service.download(id, request);
    }
}
