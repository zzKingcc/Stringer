package com.zzkingcc.stringer.agentclient.exception;

import com.zzkingcc.stringer.api.code.ErrorCode;

/**
 * Stringer 客户端异常
 *
 * @author zzkingcc
 */
public class StringerException extends RuntimeException {

    private final ErrorCode errorCode;

    public StringerException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public StringerException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 业务状态码（= errorCode.getCode()） */
    public int getCode() {
        return errorCode.getCode();
    }

    /** 状态枚举名，前端应以此定义常量而非硬编码数字 */
    public String getCodeName() {
        return errorCode.name();
    }

    /** 是否可重试 */
    public boolean isRetryable() {
        return errorCode.isRetryable();
    }
}
