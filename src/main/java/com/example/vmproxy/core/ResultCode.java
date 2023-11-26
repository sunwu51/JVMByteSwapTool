package com.example.vmproxy.core;

public enum ResultCode {
    SUCCESS(0, "SUCCESS"),
    CLASS_NOT_FOUND(1, "class not found"),
    METHOD_NOT_FOUND(2, "method not found"),

    INSTRUMENT_NOT_FOUND(3, "instrument not found"),

    INTERNAL_ERROR(-1, "internal error");

    int code;

    String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}