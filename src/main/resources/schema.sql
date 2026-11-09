-- 仅在不存在时建表，不清除已有数据。
CREATE TABLE IF NOT EXISTS student (
    id   INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    age  INT
);
