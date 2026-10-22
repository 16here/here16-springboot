# -*- coding: utf-8 -*-
import pymysql

conn = pymysql.connect(host='127.0.0.1', port=3307, user='root', password='Mall@2026Root', database='mall_db', charset='utf8mb4')
cur = conn.cursor()
# 最新订单（user_id=4）
cur.execute("SELECT id, order_no, status, total_amount, paid_at FROM orders_0 WHERE user_id=4 ORDER BY id DESC LIMIT 3")
print('MASTER orders_0 recent:', cur.fetchall(), flush=True)
cur.execute("SELECT payment_no, order_id, status, amount FROM payments ORDER BY id DESC LIMIT 3")
print('MASTER payments recent:', cur.fetchall(), flush=True)
conn.close()
