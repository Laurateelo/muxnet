package com.example.demo.mapper;

import com.example.demo.entity.Student;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface StudentMapper {
    // 指定排序，使接口测试的返回顺序稳定。
    @Select("SELECT id, name, age FROM student ORDER BY id")
    List<Student> findAll();

    @Select("SELECT id, name, age FROM student WHERE id = #{id}")
    Student findById(@Param("id") Integer id);

    // 不插入客户端的 id；使用数据库自增主键并将其回填到对象。
    @Insert("INSERT INTO student (name, age) VALUES (#{name}, #{age})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Student student);

    @Update("UPDATE student SET name = #{name}, age = #{age} WHERE id = #{id}")
    int update(Student student);

    @Delete("DELETE FROM student WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);

    // 选做一：学生总人数。
    @Select("SELECT COUNT(*) FROM student")
    int count();

    // 选做二：按姓名模糊查询；使用参数绑定，不拼接输入到 SQL 结构中。
    @Select("SELECT id, name, age FROM student "
            + "WHERE name LIKE CONCAT('%', #{name}, '%') ORDER BY id")
    List<Student> searchByName(@Param("name") String name);
}
