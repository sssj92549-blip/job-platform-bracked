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

/** JobController路由适配；业务逻辑统一由JobService处理。 */
@RestController
public class JobController {
    private final JobService service;
    public JobController(JobService service) { this.service=service; }
    @GetMapping("/api/jobs")
    public ResponseEntity<?> listPublicJobs(@RequestParam Map<String,String> query,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.list(query,"PUBLIC",request))); }
    @GetMapping("/api/jobs/{id}")
    public ResponseEntity<?> getPublicJob(@PathVariable Long id) { return ResponseEntity.status(200).body(ApiResponse.success(service.detail(id))); }
    @GetMapping("/api/companies/{id}")
    public ResponseEntity<?> getCompany(@PathVariable Long id) { return ResponseEntity.status(200).body(ApiResponse.success(service.company(id))); }
    @GetMapping("/api/company/jobs")
    public ResponseEntity<?> listCompanyJobs(@RequestParam Map<String,String> query,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.list(query,"COMPANY",request))); }
    @GetMapping("/api/company/jobs/{id}")
    public ResponseEntity<?> getCompanyJob(@PathVariable Long id,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.managedDetail(id,"COMPANY",request))); }
    @PostMapping("/api/company/jobs")
    public ResponseEntity<?> createJob(@Valid @RequestBody JobInput input,HttpServletRequest request) { return ResponseEntity.status(201).body(ApiResponse.success(service.save(null,input,request))); }
    @PutMapping("/api/company/jobs/{id}")
    public ResponseEntity<?> updateJob(@PathVariable Long id,@Valid @RequestBody JobInput input,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.save(id,input,request))); }
    @PostMapping("/api/company/jobs/{id}/submit-review")
    public ResponseEntity<?> submitReview(@PathVariable Long id,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.transition(id,"SUBMIT",null,null,request))); }
    @PostMapping("/api/company/jobs/{id}/close")
    public ResponseEntity<?> closeJob(@PathVariable Long id,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.transition(id,"CLOSE",null,null,request))); }
    @DeleteMapping("/api/company/jobs/{id}")
    public ResponseEntity<?> deleteJob(@PathVariable Long id,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.delete(id,request))); }
    @GetMapping("/api/admin/jobs")
    public ResponseEntity<?> listAdminJobs(@RequestParam Map<String,String> query,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.list(query,"ADMIN",request))); }
    @GetMapping("/api/admin/jobs/{id}")
    public ResponseEntity<?> getAdminJob(@PathVariable Long id,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.managedDetail(id,"ADMIN",request))); }
    @PostMapping("/api/admin/jobs/{id}/review")
    public ResponseEntity<?> reviewJob(@PathVariable Long id,@Valid @RequestBody Review input,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.transition(id,"ADMIN_REVIEW",input,null,request))); }
    @PostMapping("/api/admin/jobs/{id}/close")
    public ResponseEntity<?> adminCloseJob(@PathVariable Long id,@Valid @RequestBody Reason input,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.transition(id,"ADMIN_CLOSE",null,input.reason(),request))); }
}
