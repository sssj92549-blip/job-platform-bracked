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

/** ResumeController路由适配；业务逻辑统一由ResumeService处理。 */
@RestController
public class ResumeController {
    private final ResumeService service;
    public ResumeController(ResumeService service) { this.service=service; }
    @PostMapping("/api/resumes")
    public ResponseEntity<?> upload(@RequestParam MultipartFile file,HttpServletRequest request) { return ResponseEntity.status(202).body(ApiResponse.success(service.upload(file,request))); }
    @GetMapping("/api/resumes/current")
    public ResponseEntity<?> current(HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.current(request))); }
    @GetMapping("/api/resumes/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.detail(id,request))); }
    @PostMapping("/api/resumes/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable Long id,@Valid @RequestBody Confirm input,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.confirm(id,input,request))); }
    @PostMapping("/api/resumes/{id}/reparse")
    public ResponseEntity<?> reparse(@PathVariable Long id,@Valid @RequestBody Version input,HttpServletRequest request) { return ResponseEntity.status(202).body(ApiResponse.success(service.reparse(id,input,request))); }
    @PostMapping("/api/resumes/{id}/retry-index")
    public ResponseEntity<?> retryIndex(@PathVariable Long id,@Valid @RequestBody Version input,HttpServletRequest request) { return ResponseEntity.status(202).body(ApiResponse.success(service.retry(id,input,request))); }
    @DeleteMapping("/api/resumes/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.delete(id,request))); }
    @GetMapping("/api/resumes/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable Long id,HttpServletRequest request) { return service.download(id,request); }
}
