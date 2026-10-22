# -*- coding: utf-8 -*-
"""把 orders/order_items base 表残留数据按 user_id%4 并入分片（幂等 INSERT IGNORE）"""
import pymysql

conn = pymysql.connect(host='127.0.0.1', port=3307, user='root', password='Mall@2026Root',
                       database='mall_db', charset='utf8mb4', autocommit=True)
cur = conn.cursor()

# 1) orders base → 分片
for shard in [0, 1, 2, 3]:
    n = cur.execute(f"INSERT IGNORE INTO orders_{shard} SELECT * FROM orders WHERE user_id % 4 = {shard}")
    print(f'orders_0..3 migrate shard {shard}: inserted/ignored {n}', flush=True)

# 2) order_items base → 分片（按 order 归属 user 分片）
for shard in [0, 1, 2, 3]:
    n = cur.execute(
        f"INSERT IGNORE INTO order_items_{shard} "
        f"SELECT oi.* FROM order_items oi JOIN orders o ON oi.order_id = o.id "
        f"WHERE o.user_id % 4 = {shard}")
    print(f'order_items_{shard}: inserted/ignored {n}', flush=True)

# 验证
for t in ['orders_0','orders_1','orders_2','orders_3']:
    cur.execute(f"SELECT COUNT(*) FROM {t}")
    print(t, 'rows:', cur.fetchone()[0], flush=True)
cur.execute("SELECT id, order_no, user_id, status FROM orders_0 WHERE id IN (85,86)")
print('85/86 now in orders_0:', cur.fetchall(), flush=True)
cur.execute("SELECT order_id, product_name FROM order_items_0 WHERE order_id IN (85,86)")
print('85/86 items now in order_items_0:', cur.fetchall(), flush=True)
conn.close()
