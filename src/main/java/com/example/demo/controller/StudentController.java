package com.example.demo.controller;

import com.example.demo.entity.Student;
import com.example.demo.service.StudentService;
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
    public List<Student> findAll() { return studentService.findAll(); }

    @GetMapping("/count")
    public int count() { return studentService.count(); }

    @GetMapping("/search")
    public List<Student> search(@RequestParam("name") String name) {
        return studentService.searchByName(name);
    }

    @GetMapping("/{id}")
    public Student findById(@PathVariable("id") Integer id) {
        return studentService.findById(id);
    }

    @PostMapping
    public Student add(@RequestBody Student student) {
        return studentService.add(student);
    }

    @PutMapping("/{id}")
    public String update(@PathVariable("id") Integer id,
                         @RequestBody Student student) {
        student.setId(id);
        if (studentService.update(student)) {
            return "修改成功";
        }
        return "修改失败，没有编号为 " + id + " 的学生";
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable("id") Integer id) {
        if (studentService.delete(id)) {
            return "删除成功";
        }
        return "删除失败，没有编号为 " + id + " 的学生";
    }
}
