package com.axiom.agentmap.web;

import org.springframework.http.HttpStatus;

/** 계약의 오류 형식 { error, message }로 바뀌는 예외 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String error;

    public ApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public HttpStatus status() { return status; }
    public String error() { return error; }
}
