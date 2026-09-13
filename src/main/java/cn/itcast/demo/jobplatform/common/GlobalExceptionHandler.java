package cn.itcast.demo.jobplatform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 并发提交最终由数据库唯一约束兜底，不泄露索引名及SQL。 */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> duplicate(Exception e) {
        return ResponseEntity.status(409).body(ApiResponse.error(40902,"相同请求已被处理，请刷新后重试"));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessResourceFailureException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(Exception e) {
        log.error("Database or Redis unavailable", e);
        return ResponseEntity.status(503).body(ApiResponse.error(50301, "数据库或缓存服务不可用，请稍后重试"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = status == HttpStatus.NOT_FOUND ? "资源不存在" : "请求参数或方法不正确";
        int code = status == HttpStatus.NOT_FOUND ? 40401 : status.value() * 100 + 1;
        return new ResponseEntity<>(ApiResponse.error(code, message), headers, status);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> validation(ConstraintViolationException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, "参数校验失败"));
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.status(413).body(ApiResponse.error(41301, "上传文件过大"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("Unhandled request error", e);
        return ResponseEntity.status(500).body(ApiResponse.error(50001, "系统内部错误"));
    }
}
