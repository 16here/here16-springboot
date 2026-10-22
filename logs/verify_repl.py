# -*- coding: utf-8 -*-
"""验证主从复制：master 插入 -> slave 可见"""
import pymysql, time

def conn(port, db="mall_db"):
    return pymysql.connect(host="localhost", port=port, user="root",
                           password="Mall@2026Root", database=db, charset="utf8mb4", autocommit=True)

mc = conn(3307)
cur = mc.cursor()
cur.execute("INSERT INTO `users` (username, password, phone, email, avatar, status, created_at) VALUES ('repl_test', 'x', '13800000000', NULL, NULL, 1, NOW())")
uid = cur.lastrowid
print("master inserted user id:", uid, flush=True)

time.sleep(2)
sc = conn(3308)
scur = sc.cursor()
scur.execute(f"SELECT id, username FROM users WHERE id = {uid}")
r = scur.fetchone()
print("slave read:", r, flush=True)
assert r and r[0] == uid, "slave not synced!"
# 清理
mc.cur  = None
cur.execute(f"DELETE FROM users WHERE id = {uid}")
print("REPLICATION OK", flush=True)
