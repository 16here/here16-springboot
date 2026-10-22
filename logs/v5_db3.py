# -*- coding: utf-8 -*-
"""Phase 2 验证辅助 v3：修复 orders.receiver_phone 列长度（快照存 Phase1 密文）"""
import pymysql
conn = pymysql.connect(host='127.0.0.1', user='root', password='719892144Qq',
                       database='mall_db', charset='utf8mb4')
cur = conn.cursor()
cur.execute("SHOW COLUMNS FROM orders LIKE 'receiver_phone'")
print('before:', cur.fetchall())
cur.execute("ALTER TABLE orders MODIFY COLUMN receiver_phone VARCHAR(128) "
            "COMMENT '收货人手机号（Phase1 起存加密串，展示时解密）'")
conn.commit()
cur.execute("SHOW COLUMNS FROM orders LIKE 'receiver_phone'")
print('after:', cur.fetchall())
conn.close()
