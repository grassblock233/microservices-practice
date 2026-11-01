SET NAMES utf8mb4;
USE campus_user;
CREATE TABLE users (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 display_name VARCHAR(100) NOT NULL,
 status VARCHAR(32) NOT NULL,
 CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO users VALUES ('student-1','同学一','ACTIVE'),('student-2','同学二','ACTIVE'),('student-disabled','禁用用户','DISABLED');
