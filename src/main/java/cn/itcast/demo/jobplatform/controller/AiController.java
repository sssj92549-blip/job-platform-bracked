package cn.itcast.demo.jobplatform.controller;

import cn.itcast.demo.jobplatform.common.ApiResponse;
import cn.itcast.demo.jobplatform.dto.RecruitmentRequests.AiInput;
import cn.itcast.demo.jobplatform.service.AiTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** AI接口仅处理HTTP输入输出，任务授权、限流和入库都在Service。 */
@RestController
public class AiController {
    private final AiTaskService service;
    public AiController(AiTaskService service) { this.service=service; }
    @PostMapping("/api/ai/matches")
    public ResponseEntity<?> match(@Valid @RequestBody AiInput input,HttpServletRequest request) { return accepted(service.create("MATCH",input,request)); }
    @PostMapping("/api/ai/interview-questions")
    public ResponseEntity<?> interview(@Valid @RequestBody AiInput input,HttpServletRequest request) { return accepted(service.create("INTERVIEW",input,request)); }
    @PostMapping("/api/ai/assistant")
    public ResponseEntity<?> assistant(@Valid @RequestBody AiInput input,HttpServletRequest request) { return accepted(service.create("ASSISTANT",input,request)); }
    @GetMapping("/api/ai/tasks/{id}")
    public ResponseEntity<?> task(@PathVariable Long id,HttpServletRequest request) { return ResponseEntity.ok(ApiResponse.success(service.get(id,request))); }
    private ResponseEntity<?> accepted(AiTaskService.Submission submission) {
        return ResponseEntity.status(submission.reused()?200:202).body(ApiResponse.success(submission.task()));
    }
}
