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

/** TalentController路由适配；业务逻辑统一由TalentService处理。 */
@RestController
public class TalentController {
    private final TalentService service;
    public TalentController(TalentService service) { this.service=service; }
    @PostMapping("/api/company/jobs/{id}/talent-search")
    public ResponseEntity<?> search(@PathVariable Long id,@Valid @RequestBody Talent input,HttpServletRequest request) { return ResponseEntity.status(200).body(ApiResponse.success(service.search(id,input,request))); }
}
