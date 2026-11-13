package com.blockbattle.common.api;

/** REST 응답 봉투(API 명세 1.1). 성공이면 error = null, 실패면 data = null. */
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public record ApiError(String code, String message) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> fail(String code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code, message));
    }
}
