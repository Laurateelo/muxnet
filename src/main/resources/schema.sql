-- 幂等建表，不在启动时清空已有数据，保证重启后数据仍然保留。
CREATE TABLE IF NOT EXISTS student (
    id   INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    age  INT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
