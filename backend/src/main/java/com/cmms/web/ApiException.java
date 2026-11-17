package com.cmms.web;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " " + id + " not found");
    }
    public static ApiException bad(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, msg); }
    public static ApiException conflict(String msg) { return new ApiException(HttpStatus.CONFLICT, msg); }
    public static ApiException forbidden(String msg) { return new ApiException(HttpStatus.FORBIDDEN, msg); }
}
