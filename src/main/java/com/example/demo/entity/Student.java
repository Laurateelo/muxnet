package com.example.demo.entity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class Student {
    private Integer id;

    @NotBlank(message = "姓名不能为空")
    private String name;

    @NotNull(message = "年龄不能为空")
    @Min(value = 1, message = "年龄必须在 1 到 120 之间")
    @Max(value = 120, message = "年龄必须在 1 到 120 之间")
    private Integer age;

    public Student() { }

    public Student(Integer id, String name, Integer age) {
        this.id = id;
        this.name = name;
        this.age = age;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
}
