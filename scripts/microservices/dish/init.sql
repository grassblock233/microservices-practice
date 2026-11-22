SET NAMES utf8mb4;
USE campus_dish;
CREATE TABLE IF NOT EXISTS dishes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(64),
    price DECIMAL(19, 2) NOT NULL,
    stock INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_dishes_merchant_status_id (merchant_id, status, id),
    CONSTRAINT ck_dishes_price CHECK (price >= 0),
    CONSTRAINT ck_dishes_stock CHECK (stock >= 0),
    CONSTRAINT ck_dishes_status CHECK (status IN ('ON_SALE', 'OFF_SALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO dishes (id, merchant_id, name, category, price, stock, status) VALUES
    (1, 10, '鸡腿饭', '主食', 12.50, 18, 'ON_SALE'),
    (2, 10, '牛肉面', '主食', 16.00, 29, 'ON_SALE'),
    (3, 10, '柠檬茶', '饮品', 5.00, 0, 'ON_SALE'),
    (4, 10, '红烧肉饭', '主食', 18.00, 10, 'OFF_SALE'),
    (5, 20, '蔬菜沙拉', '轻食', 15.00, 19, 'ON_SALE'),
    (6, 20, '豆浆', '饮品', 3.00, 39, 'ON_SALE')
ON DUPLICATE KEY UPDATE id = dishes.id;
