package cn.itcast.demo.jobplatform.common;

import org.slf4j.MDC;

public record ApiResponse<T>(int code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data, MDC.get("requestId"));
    }

    public static ApiResponse<Void> error(int code, String message) {
        return new ApiResponse<>(code, message, null, MDC.get("requestId"));
    }
}
