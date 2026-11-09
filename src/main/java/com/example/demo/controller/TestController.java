package com.example.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅供本次实验演示异常兜底，实际业务部署时应删除这个测试接口。 */
@RestController
public class TestController {
    @GetMapping("/test/error")
    public String error() {
        int a = 10 / 0;
        return "这一行永远执行不到";
    }
}
