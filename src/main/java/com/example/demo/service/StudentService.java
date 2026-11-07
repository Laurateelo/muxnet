package com.example.demo.service;

import com.example.demo.entity.Student;
import com.example.demo.mapper.StudentMapper;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class StudentService {
    private final StudentMapper studentMapper;

    // 单构造方法由 Spring 自动注入，与指导书的 @Autowired 字段注入等效。
    public StudentService(StudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    public List<Student> findAll() { return studentMapper.findAll(); }
    public Student findById(Integer id) { return studentMapper.findById(id); }

    public Student add(Student student) {
        studentMapper.insert(student);
        return student;
    }

    public boolean update(Student student) {
        return studentMapper.update(student) > 0;
    }

    public boolean delete(Integer id) {
        return studentMapper.deleteById(id) > 0;
    }

    public int count() { return studentMapper.count(); }
    public List<Student> searchByName(String name) {
        return studentMapper.searchByName(name);
    }
}
