package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.entity.Student;
import com.example.demo.service.StudentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/students")
public class StudentController {
    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    @GetMapping
    public Result<List<Student>> findAll() {
        return Result.success(studentService.findAll());
    }

    @GetMapping("/{id}")
    public Result<Student> findById(@PathVariable Integer id) {
        return Result.success(studentService.findById(id));
    }

    @PostMapping
    public Result<Student> add(@Valid @RequestBody Student student) {
        return Result.success(studentService.add(student));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Integer id,
                               @Valid @RequestBody Student student) {
        student.setId(id); // URL 中的编号优先，防止请求体的编号覆盖操作目标。
        studentService.update(student);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        studentService.delete(id);
        return Result.success();
    }
}
