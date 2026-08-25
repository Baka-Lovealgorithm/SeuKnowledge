package com.ai.konwledgerepo.common;

/**
 * 全库统一错误码常量（ApiResponse.code / BizException code）。
 * 约定：HTTP 语义（401/403/429 等）与业务码均经此集中定义，由 GlobalExceptionHandler 统一映射。
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    /** 参数校验失败 / 常规业务错误 */
    public static final int BAD_REQUEST = 400;
    /** 未登录或会话失效 */
    public static final int UNAUTHORIZED = 401;
    /** 无权访问（归属校验 / 角色不足） */
    public static final int FORBIDDEN = 403;
    /** 资源不存在 */
    public static final int NOT_FOUND = 404;
    /** 限流 / 重复提交 */
    public static final int TOO_MANY_REQUESTS = 429;
    /** 服务器内部错误 */
    public static final int INTERNAL_ERROR = 500;
}
