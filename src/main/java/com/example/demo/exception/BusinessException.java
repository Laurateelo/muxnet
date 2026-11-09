package com.example.demo.exception;

/** 业务失败用异常向上层传递，统一由全局处理器生成响应。 */
public class BusinessException extends RuntimeException {
    private final Integer code;

    public BusinessException(Integer code, String msg) {
        super(msg);
        this.code = code;
    }

    public Integer getCode() { return code; }
}
