package com.example.demo.service;

import com.example.demo.entity.Student;
import com.example.demo.exception.BusinessException;
import com.example.demo.mapper.StudentMapper;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class StudentService {
    private final StudentMapper studentMapper;

    public StudentService(StudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    public List<Student> findAll() {
        return studentMapper.findAll();
    }

    public Student findById(Integer id) {
        Student student = studentMapper.findById(id);
        if (student == null) {
            throw new BusinessException(404, "没有编号为 " + id + " 的学生");
        }
        return student;
    }

    public Student add(Student student) {
        if (studentMapper.findByName(student.getName()) != null) {
            throw new BusinessException(400, "姓名已存在");
        }
        studentMapper.insert(student);
        return student;
    }

    public void update(Student student) {
        if (studentMapper.update(student) == 0) {
            throw new BusinessException(404, "没有编号为 " + student.getId() + " 的学生");
        }
    }

    public void delete(Integer id) {
        if (studentMapper.deleteById(id) == 0) {
            throw new BusinessException(404, "没有编号为 " + id + " 的学生");
        }
    }
}
