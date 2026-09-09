package cn.itcast.demo.jobplatform.common;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final int code;
    private final HttpStatus status;

    public BusinessException(HttpStatus status, int code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
