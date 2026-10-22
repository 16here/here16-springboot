-- =====================================================
-- Phase 3 分表 DDL（在 MySQL master 3307 上执行）
-- 1) orders / order_items：按 user_id % 4 分表（orders_0~3）
-- 2) user_behaviors / inventory_logs：按月分表（_YYYYMM）
-- 3) reconciliation_logs：对账结果表
-- 说明：原表保留（作为"未分表"兼容视图/备份），后端改造后读写只走分表。
-- =====================================================

-- ---------- 1. 订单表按用户分表（4 张） ----------
CREATE TABLE IF NOT EXISTS orders_0 LIKE orders;
CREATE TABLE IF NOT EXISTS orders_1 LIKE orders;
CREATE TABLE IF NOT EXISTS orders_2 LIKE orders;
CREATE TABLE IF NOT EXISTS orders_3 LIKE orders;
CREATE TABLE IF NOT EXISTS order_items_0 LIKE order_items;
CREATE TABLE IF NOT EXISTS order_items_1 LIKE order_items;
CREATE TABLE IF NOT EXISTS order_items_2 LIKE order_items;
CREATE TABLE IF NOT EXISTS order_items_3 LIKE order_items;

-- 迁移存量订单（按 user_id % 4 分布）
INSERT INTO orders_0 SELECT * FROM orders WHERE user_id % 4 = 0;
INSERT INTO orders_1 SELECT * FROM orders WHERE user_id % 4 = 1;
INSERT INTO orders_2 SELECT * FROM orders WHERE user_id % 4 = 2;
INSERT INTO orders_3 SELECT * FROM orders WHERE user_id % 4 = 3;

-- 迁移存量明细（跟随订单 user_id 路由到同一分片，保证同单明细同表）
INSERT INTO order_items_0 SELECT oi.* FROM order_items oi JOIN orders o ON oi.order_id = o.id WHERE o.user_id % 4 = 0;
INSERT INTO order_items_1 SELECT oi.* FROM order_items oi JOIN orders o ON oi.order_id = o.id WHERE o.user_id % 4 = 1;
INSERT INTO order_items_2 SELECT oi.* FROM order_items oi JOIN orders o ON oi.order_id = o.id WHERE o.user_id % 4 = 2;
INSERT INTO order_items_3 SELECT oi.* FROM order_items oi JOIN orders o ON oi.order_id = o.id WHERE o.user_id % 4 = 3;

-- 分片自增起点错开（存量 id 保留；新订单用雪花 ID，互不冲突）
ALTER TABLE orders_0 AUTO_INCREMENT = 1000;
ALTER TABLE orders_1 AUTO_INCREMENT = 100000;
ALTER TABLE orders_2 AUTO_INCREMENT = 200000;
ALTER TABLE orders_3 AUTO_INCREMENT = 300000;
ALTER TABLE order_items_0 AUTO_INCREMENT = 1000;
ALTER TABLE order_items_1 AUTO_INCREMENT = 100000;
ALTER TABLE order_items_2 AUTO_INCREMENT = 200000;
ALTER TABLE order_items_3 AUTO_INCREMENT = 300000;

-- ---------- 2. 行为/库存流水按月分表（当月 _202610） ----------
CREATE TABLE IF NOT EXISTS user_behaviors_202610 LIKE user_behaviors;
CREATE TABLE IF NOT EXISTS inventory_logs_202610 LIKE inventory_logs;

-- 迁移存量（只取当月；更早数据保留在原表，作为历史冷数据）
INSERT INTO user_behaviors_202610
SELECT * FROM user_behaviors WHERE DATE_FORMAT(created_at, '%Y%m') = '202610';
INSERT INTO inventory_logs_202610
SELECT * FROM inventory_logs WHERE DATE_FORMAT(created_at, '%Y%m') = '202610';

-- ---------- 3. 对账结果表 ----------
CREATE TABLE IF NOT EXISTS reconciliation_logs (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '对账记录主键',
  biz_date      VARCHAR(10)  NOT NULL COMMENT '对账日期（YYYY-MM-DD）',
  item_type     VARCHAR(30)  NOT NULL COMMENT '对账项类型：PAYMENT_ORDER 支付-订单 缺失/状态不一致 / AMOUNT 金额不一致 / INVENTORY 库存扣减异常',
  ref_id        BIGINT       DEFAULT NULL COMMENT '关联业务 ID（订单/支付流水）',
  ref_no        VARCHAR(32)  DEFAULT NULL COMMENT '关联业务单号（订单号/支付流水号）',
  issue         VARCHAR(500) NOT NULL COMMENT '问题描述',
  status        VARCHAR(10)  NOT NULL DEFAULT 'OPEN' COMMENT '处理状态：OPEN 待处理 / IGNORED 已忽略',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发现时间',
  PRIMARY KEY (id),
  KEY idx_date_type (biz_date, item_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账结果表（Phase 3：支付-订单-库存对账）';
