package com.example.demo.entity;

/** 学生实体，id 是数据库自动生成的编号。 */
public class Student {
    private Integer id;
    private String name;
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
