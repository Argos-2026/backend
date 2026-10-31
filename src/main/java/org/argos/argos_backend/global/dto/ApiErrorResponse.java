package org.argos.argos_backend.global.dto;

public record ApiErrorResponse(ApiError error) {
    public record ApiError(String code, String message) {}

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(new ApiError(code, message));
    }
}
