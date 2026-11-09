package com.example.demo.exception;

import com.example.demo.common.Result;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError error = e.getBindingResult().getFieldError();
        String msg = error == null ? "请求参数校验失败" : error.getDefaultMessage();
        return Result.error(400, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleJsonException(HttpMessageNotReadableException e) {
        return Result.error(400, "请求数据格式错误，请检查 JSON");
    }

    // 选做一：地址拼写错误应返回 404 业务码。
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNotFoundException(NoResourceFoundException e) {
        return Result.error(404, "接口地址不存在");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 按指导书保留完整控制台堆栈；不把内部异常详情返回前端。
        e.printStackTrace();
        return Result.error(500, "服务器内部错误，请联系管理员");
    }
}
