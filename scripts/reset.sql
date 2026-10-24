-- 警告：删除 campus_takeaway 整个数据库（包括所有表和数据），然后恢复示例数据。
-- 仅用于本地开发/测试；先停止应用，避免重置时发生读写。
-- 必须在仓库根目录使用 mysql 命令行客户端执行：
-- mysql --default-character-set=utf8mb4 -u root -p < scripts/reset.sql
-- SOURCE 是 mysql 客户端指令，路径相对于客户端的当前工作目录。
DROP DATABASE IF EXISTS campus_takeaway;
SOURCE scripts/init.sql;
