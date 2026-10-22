# -*- coding: utf-8 -*-
import pymysql

conn = pymysql.connect(host='127.0.0.1', port=3307, user='root', password='Mall@2026Root', database='mall_db', charset='utf8mb4')
cur = conn.cursor()

# base 表 orders 全量（id 4~86），检查哪些不在分片
cur.execute("SELECT id, user_id, status FROM orders ORDER BY id")
base_orders = cur.fetchall()
print('base orders:', len(base_orders), flush=True)

shard_ids = set()
for t in ['orders_0','orders_1','orders_2','orders_3']:
    cur.execute(f"SELECT id FROM {t}")
    shard_ids.update(r[0] for r in cur.fetchall())
print('shard total ids:', len(shard_ids), flush=True)

missing = [r for r in base_orders if r[0] not in shard_ids]
print('orders in base but missing in shards:', missing, flush=True)

# 85/86 的 order_items 在哪
for t in ['order_items', 'order_items_0','order_items_1','order_items_2','order_items_3']:
    cur.execute(f"SELECT order_id, product_name FROM {t} WHERE order_id IN (85,86)")
    rows = cur.fetchall()
    if rows:
        print('order_items in', t, rows, flush=True)

# 85/86 的 status log
cur.execute("SELECT order_id, from_status, to_status FROM order_status_log WHERE order_id IN (85,86)")
print('status logs for 85/86:', cur.fetchall(), flush=True)

# 85/86 的 payments
cur.execute("SELECT order_id, payment_no, status FROM payments WHERE order_id IN (85,86)")
print('payments for 85/86:', cur.fetchall(), flush=True)
conn.close()
