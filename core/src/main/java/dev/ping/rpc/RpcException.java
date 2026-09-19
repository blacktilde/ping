package dev.ping.rpc;

/** A JSON-RPC error that should be reported to the caller rather than crashing the core. */
public class RpcException extends RuntimeException {

    /** JSON-RPC 2.0 reserved codes. Application codes start at -32000. */
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

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
}
