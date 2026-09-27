package com.zzkingcc.stringer.agentclient.exception;

import com.zzkingcc.stringer.api.code.ErrorCode;

/**
 * Stringer 客户端<b>启动期</b>异常
 *
 * @author zzkingcc
 */
public class StringerStartupException extends StringerException {

    public StringerStartupException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public StringerStartupException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
