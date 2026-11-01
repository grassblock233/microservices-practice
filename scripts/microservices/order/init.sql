SET NAMES utf8mb4;
USE campus_order;
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
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_subtotal CHECK (subtotal >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE order_attempts (
 order_no VARCHAR(36) NOT NULL PRIMARY KEY,
 user_id VARCHAR(64) NOT NULL,
 merchant_id BIGINT NOT NULL,
 request_items TEXT NOT NULL,
 status VARCHAR(32) NOT NULL,
 error_code VARCHAR(64),
 failure_detail VARCHAR(1000),
 updated_at DATETIME(6) NOT NULL,
 KEY idx_attempt_status_time (status,updated_at),
 CONSTRAINT ck_attempt_status CHECK (status IN ('PROCESSING','SUCCEEDED','FAILED','RECOVERY_REQUIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
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
