package io.formkiln.server.platform;

import org.springframework.http.HttpStatus;

/** A client error of the runtime API with an HTTP status and a stable code. */
public class RuntimeException4xx extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public RuntimeException4xx(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
