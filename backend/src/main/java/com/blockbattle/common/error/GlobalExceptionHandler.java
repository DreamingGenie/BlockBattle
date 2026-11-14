package com.blockbattle.common.error;

import com.blockbattle.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handle(ApiException e) {
        return toResponse(e.code());
    }

    /** POL-026: 어떤 API에도 매핑되지 않은 요청. 클라이언트 실수라 서버 오류로 기록하지 않는다 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        return toResponse(ErrorCode.NOT_FOUND);
    }

    /** POL-026: 경로는 있으나 지원하지 않는 메서드. Allow 헤더로 지원 메서드를 알린다 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
        HttpHeaders headers = new HttpHeaders();
        if (e.getSupportedHttpMethods() != null) headers.setAllow(e.getSupportedHttpMethods());
        return ResponseEntity.status(code.status()).headers(headers).body(ApiResponse.fail(code.name(), code.message()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("처리하지 못한 예외", e);
        return toResponse(ErrorCode.INTERNAL_ERROR);
    }

    public static ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code.name(), code.message()));
    }
}
