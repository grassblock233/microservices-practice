SET NAMES utf8mb4;
USE campus_user;
CREATE TABLE users (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 display_name VARCHAR(100) NOT NULL,
 status VARCHAR(32) NOT NULL,
 username VARCHAR(64) NULL,
 role VARCHAR(16) COLLATE utf8mb4_bin NULL,
 password_hash VARCHAR(60) NULL,
 CONSTRAINT uk_users_username UNIQUE (username),
 CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','DISABLED')),
 CONSTRAINT ck_users_credentials CHECK (
  (username IS NULL AND role IS NULL AND password_hash IS NULL)
  OR (username IS NOT NULL AND role IS NOT NULL AND role IN ('STUDENT','ADMIN') AND password_hash IS NOT NULL)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO users (id, display_name, status) VALUES
 ('student-1','同学一','ACTIVE'),('student-2','同学二','ACTIVE'),('student-disabled','禁用用户','DISABLED');
