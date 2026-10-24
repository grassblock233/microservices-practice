-- mysql --default-character-set=utf8mb4 -u root -p < scripts/init.sql
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS campus_takeaway
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE campus_takeaway;

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

CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no CHAR(36) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    merchant_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(19, 2) NOT NULL,
    delivery_address VARCHAR(255) NOT NULL,
    remark VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_no UNIQUE (order_no),
    KEY idx_orders_user_created_id (user_id, created_at, id),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0),
    CONSTRAINT ck_orders_status CHECK (status = 'PENDING_PAYMENT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    dish_id BIGINT NOT NULL,
    dish_name VARCHAR(100) NOT NULL,
    unit_price DECIMAL(19, 2) NOT NULL,
    quantity INT NOT NULL,
    subtotal DECIMAL(19, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_items_order_dish UNIQUE (order_id, dish_id),
    KEY idx_order_items_dish_id (dish_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_order_items_dish FOREIGN KEY (dish_id) REFERENCES dishes (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_subtotal CHECK (subtotal >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;

-- 固定 ID 便于接口测试；库存已扣除下方示例订单的购买数量。
-- 商家 10：正常售卖、售罄、下架；商家 20：用于验证跨商家下单。
INSERT INTO dishes (id, merchant_id, name, category, price, stock, status) VALUES
    (1, 10, '鸡腿饭', '主食', 12.50, 18, 'ON_SALE'),
    (2, 10, '牛肉面', '主食', 16.00, 29, 'ON_SALE'),
    (3, 10, '柠檬茶', '饮品', 5.00, 0, 'ON_SALE'),
    (4, 10, '红烧肉饭', '主食', 18.00, 10, 'OFF_SALE'),
    (5, 20, '蔬菜沙拉', '轻食', 15.00, 19, 'ON_SALE'),
    (6, 20, '豆浆', '饮品', 3.00, 39, 'ON_SALE')
ON DUPLICATE KEY UPDATE id = dishes.id;

-- UUID、学生标识、创建时间固定，方便验证列表排序及按订单号查询。
INSERT INTO orders
    (id, order_no, user_id, merchant_id, status, total_amount, delivery_address, remark, created_at) VALUES
    (1, '00000000-0000-4000-8000-000000000001', 'student-1', 10,
     'PENDING_PAYMENT', 25.00, '学生宿舍 1 幢 101 室', '少辣', '2026-10-24 12:00:00.123456'),
    (2, '00000000-0000-4000-8000-000000000002', 'student-1', 10,
     'PENDING_PAYMENT', 16.00, '学生宿舍 1 幢 101 室', NULL, '2026-10-24 12:30:00.123456'),
    (3, '00000000-0000-4000-8000-000000000003', 'student-2', 20,
     'PENDING_PAYMENT', 18.00, '学生宿舍 2 幢 202 室', '送到楼下', '2026-10-24 12:30:00.123456')
ON DUPLICATE KEY UPDATE id = orders.id;

INSERT INTO order_items
    (id, order_id, dish_id, dish_name, unit_price, quantity, subtotal) VALUES
    (1, 1, 1, '鸡腿饭', 12.50, 2, 25.00),
    (2, 2, 2, '牛肉面', 16.00, 1, 16.00),
    (3, 3, 5, '蔬菜沙拉', 15.00, 1, 15.00),
    (4, 3, 6, '豆浆', 3.00, 1, 3.00)
ON DUPLICATE KEY UPDATE id = order_items.id;

COMMIT;
