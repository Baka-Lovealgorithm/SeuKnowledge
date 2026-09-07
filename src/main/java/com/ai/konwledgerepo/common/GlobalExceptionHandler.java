package com.ai.konwledgerepo.common;

import com.ai.konwledgerepo.config.props.SeuFileProperties;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理，统一转 ApiResponse（错误码见 {@link ErrorCodes}）。
 * SSE 客户端断开（AsyncRequestNotUsableException）静默处理，避免噪音日志与对已提交响应的二次写入。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final SeuFileProperties fileProps;

    public GlobalExceptionHandler(SeuFileProperties fileProps) {
        this.fileProps = fileProps;
    }

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return ApiResponse.error(ErrorCodes.BAD_REQUEST, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.error(ErrorCodes.BAD_REQUEST, "缺少请求参数: " + e.getParameterName());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getMessage())
                .orElse("参数校验失败");
        return ApiResponse.error(ErrorCodes.BAD_REQUEST, msg);
    }

    /** 路径/查询参数类型不匹配（如 chunk id 被前端拼成 undefined）属于客户端请求错误，而非服务端故障。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ApiResponse.error(ErrorCodes.BAD_REQUEST, "参数格式错误: " + e.getName());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiResponse<Void> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("数据完整性冲突: {}", e.getMessage());
        return ApiResponse.error(ErrorCodes.BAD_REQUEST, "数据操作冲突，请检查关联数据");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ApiResponse.error(ErrorCodes.BAD_REQUEST,
                "文件超过大小上限 " + (fileProps.maxSize() / 1024 / 1024) + "MB");
    }

    /** SSE 客户端断开：响应已不可用，静默忽略（仅 debug 日志），不返回响应体 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ApiResponse<Void> handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.debug("SSE 客户端连接已断开: {}", e.getMessage());
        return null;
    }

    @ExceptionHandler(Exception.class)
    public Object handleOther(Exception e) throws Exception {
        // Spring 框架已定义的客户端错误（405/404/415 等）及 SSE 响应已提交场景，交还框架默认处理
        if (e instanceof HttpRequestMethodNotSupportedException
                || e instanceof NoResourceFoundException
                || e instanceof NoHandlerFoundException
                || e instanceof HttpMediaTypeNotSupportedException
                || e instanceof HttpMessageNotReadableException
                || e instanceof HttpMessageNotWritableException) {
            throw e;
        }
        log.error("Unhandled exception", e);
        return ApiResponse.error(ErrorCodes.INTERNAL_ERROR, "服务器内部错误");
    }
}
