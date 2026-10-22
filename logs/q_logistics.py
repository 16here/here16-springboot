# -*- coding: utf-8 -*-
import pymysql

OID = 106378054632210432
conn = pymysql.connect(host='127.0.0.1', port=3307, user='root', password='Mall@2026Root', database='mall_db', charset='utf8mb4')
cur = conn.cursor()

# 广播查订单（4 张分片 + base）
for t in ['orders_0', 'orders_1', 'orders_2', 'orders_3', 'orders']:
    cur.execute(f"SELECT id, user_id, order_no, status, total_amount, shipping_no, shipped_at FROM {t} WHERE id = %s", (OID,))
    rows = cur.fetchall()
    if rows:
        print(t, '->', rows, flush=True)

# 运单
cur.execute("SELECT id, order_id, order_no, shipping_company, tracking_no, status FROM shipping WHERE order_id = %s", (OID,))
print('shipping:', cur.fetchall(), flush=True)

# 支付流水
cur.execute("SELECT payment_no, order_id, status, amount FROM payments WHERE order_id = %s", (OID,))
print('payments:', cur.fetchall(), flush=True)

# 状态日志
cur.execute("SELECT id, order_id, from_status, to_status, event, remark FROM order_status_log WHERE order_id = %s ORDER BY id", (OID,))
print('status_log:', cur.fetchall(), flush=True)
conn.close()
