package com.example.demo.mapper;

import com.example.demo.entity.Student;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface StudentMapper {
    @Select("SELECT * FROM student ORDER BY id")
    List<Student> findAll();

    @Select("SELECT * FROM student WHERE id = #{id}")
    Student findById(Integer id);

    // 选做二：新增前查询是否已经存在同名学生。
    @Select("SELECT * FROM student WHERE name = #{name} LIMIT 1")
    Student findByName(String name);

    @Insert("INSERT INTO student (name, age) VALUES (#{name}, #{age})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Student student);

    @Update("UPDATE student SET name = #{name}, age = #{age} WHERE id = #{id}")
    int update(Student student);

    @Delete("DELETE FROM student WHERE id = #{id}")
    int deleteById(Integer id);
}
