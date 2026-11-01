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


CREATE TABLE stock_operations (
 order_no VARCHAR(36) NOT NULL PRIMARY KEY,
 request_digest VARCHAR(64),
 status VARCHAR(32) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_stock_operations_status CHECK (status IN ('DEDUCTED','RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE stock_operation_items (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_no VARCHAR(36) NOT NULL,
 dish_id BIGINT NOT NULL,
 dish_name VARCHAR(100) NOT NULL,
 unit_price DECIMAL(19,2) NOT NULL,
 quantity INT NOT NULL,
 CONSTRAINT uk_stock_operation_dish UNIQUE (order_no,dish_id),
 CONSTRAINT fk_stock_items_operation FOREIGN KEY (order_no) REFERENCES stock_operations(order_no),
 CONSTRAINT ck_stock_items_quantity CHECK (quantity>0),
 CONSTRAINT ck_stock_items_price CHECK (unit_price>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO dishes (id, merchant_id, name, category, price, stock, status) VALUES
    (1, 10, '鸡腿饭', '主食', 12.50, 18, 'ON_SALE'),
    (2, 10, '牛肉面', '主食', 16.00, 29, 'ON_SALE'),
    (3, 10, '柠檬茶', '饮品', 5.00, 0, 'ON_SALE'),
    (4, 10, '红烧肉饭', '主食', 18.00, 10, 'OFF_SALE'),
    (5, 20, '蔬菜沙拉', '轻食', 15.00, 19, 'ON_SALE'),
    (6, 20, '豆浆', '饮品', 3.00, 39, 'ON_SALE')
ON DUPLICATE KEY UPDATE id = dishes.id;


-- The historic seed orders already consumed stock. Keep matching operation snapshots for reconciliation.
INSERT INTO stock_operations (order_no,request_digest,status,updated_at) VALUES
('00000000-0000-4000-8000-000000000001',SHA2('10:[1=2]',256),'DEDUCTED',NOW(6)),
('00000000-0000-4000-8000-000000000002',SHA2('10:[2=1]',256),'DEDUCTED',NOW(6)),
('00000000-0000-4000-8000-000000000003',SHA2('20:[5=1, 6=1]',256),'DEDUCTED',NOW(6));
INSERT INTO stock_operation_items (order_no,dish_id,dish_name,unit_price,quantity) VALUES
('00000000-0000-4000-8000-000000000001',1,'鸡腿饭',12.50,2),
('00000000-0000-4000-8000-000000000002',2,'牛肉面',16.00,1),
('00000000-0000-4000-8000-000000000003',5,'蔬菜沙拉',15.00,1),
('00000000-0000-4000-8000-000000000003',6,'豆浆',3.00,1);
