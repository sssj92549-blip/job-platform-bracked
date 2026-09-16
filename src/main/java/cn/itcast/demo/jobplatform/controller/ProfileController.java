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
 * ProfileController路由适配；业务逻辑统一由ProfileService处理。
 */
@RestController
public class ProfileController {
    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @PutMapping("/api/users/me/profile")
    public ResponseEntity<?> updatePersonal(@Valid @RequestBody Personal input, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.personal(input, request)));
    }

    @PutMapping("/api/company/profile")
    public ResponseEntity<?> updateCompany(@Valid @RequestBody Company input, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.company(input, request)));
    }

    @PostMapping("/api/company/profile/submit-review")
    public ResponseEntity<?> submitReview(HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.submit(request)));
    }

    @PatchMapping("/api/users/me/discoverability")
    public ResponseEntity<?> updateDiscoverability(@Valid @RequestBody Discoverability input, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.discover(input, request)));
    }

    @PostMapping("/api/users/me/avatar")
    public ResponseEntity<?> uploadAvatar(@RequestParam MultipartFile file, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.avatar(file, request)));
    }

    @GetMapping("/api/users/{id}/avatar")
    public ResponseEntity<Resource> downloadAvatar(@PathVariable Long id) {
        return service.avatar(id);
    }

    @GetMapping("/api/admin/users")
    public ResponseEntity<?> listUsers(@RequestParam Map<String, String> query, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.list(query, request)));
    }

    @GetMapping("/api/admin/users/{id}")
    public ResponseEntity<?> getUser(@PathVariable Long id, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.detail(id, request)));
    }

    @PostMapping("/api/admin/users/{id}/review")
    public ResponseEntity<?> reviewUser(@PathVariable Long id, @Valid @RequestBody Review input, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.manage(id, input, null, request)));
    }

    @PatchMapping("/api/admin/users/{id}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable Long id, @Valid @RequestBody Enabled input, HttpServletRequest request) {
        return ResponseEntity.status(200).body(ApiResponse.success(service.manage(id, null, input, request)));
    }
}
