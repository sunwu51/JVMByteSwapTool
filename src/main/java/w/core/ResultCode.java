package w.core;

public enum ResultCode {
    SUCCESS(0, "SUCCESS"),
    CLASS_OR_METHOD_NOT_FOUND(1, "class or method not found"),
    INSTRUMENT_NOT_FOUND(2, "instrument not found"),

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