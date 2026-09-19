package dev.ping.rpc;

/** A JSON-RPC error that should be reported to the caller rather than crashing the core. */
public class RpcException extends RuntimeException {

    /** JSON-RPC 2.0 reserved codes. Application codes start at -32000. */
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /** The caller aborted this request through {@code http.cancel}. */
    public static final int REQUEST_CANCELLED = -32001;

    /** The request was well formed but the exchange failed: DNS, connect, TLS or timeout. */
    public static final int REQUEST_FAILED = -32002;

    /** The collection store could not read, parse or write a file. */
    public static final int STORE_FAILED = -32003;

    /** Authentication could not be applied: bad configuration or a failed token exchange. */
    public static final int AUTH_FAILED = -32004;

    private final int code;

    public RpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public RpcException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static RpcException methodNotFound(String method) {
        return new RpcException(METHOD_NOT_FOUND, "Unknown method: " + method);
    }

    public static RpcException invalidParams(String detail) {
        return new RpcException(INVALID_PARAMS, detail);
    }

    public static RpcException storeFailed(String detail) {
        return new RpcException(STORE_FAILED, detail);
    }

    public static RpcException storeFailed(String detail, Throwable cause) {
        return new RpcException(STORE_FAILED, detail, cause);
    }

    public static RpcException authFailed(String detail) {
        return new RpcException(AUTH_FAILED, detail);
    }

    public static RpcException authFailed(String detail, Throwable cause) {
        return new RpcException(AUTH_FAILED, detail, cause);
    }
}
