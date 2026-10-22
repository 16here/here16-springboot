# -*- coding: utf-8 -*-
"""补运单：订单已发货（status>=2 且 shipping_no 非空）但 shipping 表无运单 → 补 insert"""
import pymysql

conn = pymysql.connect(host='127.0.0.1', port=3307, user='root', password='Mall@2026Root',
                       database='mall_db', charset='utf8mb4', autocommit=True)
cur = conn.cursor()

total = 0
for t in ['orders_0', 'orders_1', 'orders_2', 'orders_3']:
    # 已发货（status=2 待收货 / 3 已完成）且订单表有单号，但 shipping 表无运单
    sql = (
        f"INSERT INTO shipping (order_id, order_no, shipping_company, tracking_no, status) "
        f"SELECT o.id, o.order_no, '顺丰速运', o.shipping_no, 0 FROM {t} o "
        f"WHERE o.status >= 2 AND o.shipping_no IS NOT NULL AND o.shipping_no != '' "
        f"AND NOT EXISTS (SELECT 1 FROM shipping s WHERE s.order_id = o.id)"
    )
    n = cur.execute(sql)
    print(t, 'backfill:', n, flush=True)
    total += n

# 验证
cur.execute("SELECT id, order_id, order_no, shipping_company, tracking_no, status FROM shipping ORDER BY id DESC LIMIT 10")
print('recent shipping:', cur.fetchall(), flush=True)
cur.execute("SELECT COUNT(*) FROM shipping")
print('shipping total:', cur.fetchone()[0], flush=True)
conn.close()
