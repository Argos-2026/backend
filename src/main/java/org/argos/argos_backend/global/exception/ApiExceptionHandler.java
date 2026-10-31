package org.argos.argos_backend.global.exception;

import org.argos.argos_backend.global.dto.ApiErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) { return response(e.status(), e.code(), e.getMessage()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() { return response(400, "INVALID_REQUEST", "올바른 JSON 요청 본문을 입력해 주세요."); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> media() { return response(415, "UNSUPPORTED_MEDIA_TYPE", "application/json 형식으로 요청해 주세요."); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> notFound() { return response(404, "NOT_FOUND", "요청한 API를 찾을 수 없습니다."); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method() { return response(405, "METHOD_NOT_ALLOWED", "허용되지 않는 요청 방식입니다."); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected() { return response(500, "INTERNAL_SERVER_ERROR", "요청 처리 중 오류가 발생했습니다."); }

    private ResponseEntity<?> response(int status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
            .body(ApiErrorResponse.of(code, message));
    }
}
