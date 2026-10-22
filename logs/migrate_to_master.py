# -*- coding: utf-8 -*-
"""Phase3 数据迁移：宿主 MySQL(3306) mall_db -> Docker master(3307)。
逐表 SHOW CREATE TABLE + 数据搬运，保留自增值与字符集。
"""
import pymysql

SRC = dict(host="localhost", port=3306, user="root", password="719892144Qq", charset="utf8mb4")
DST = dict(host="localhost", port=3307, user="root", password="Mall@2026Root", charset="utf8mb4")
DB = "mall_db"

def get_conn(cfg):
    c = pymysql.connect(**cfg, database=DB)
    return c

sc = get_conn(SRC)
dc = get_conn(DST)
scur = sc.cursor()
dcur = dc.cursor()

scur.execute("SHOW TABLES")
tables = [r[0] for r in scur.fetchall()]
print(f"tables: {len(tables)}", flush=True)

for t in tables:
    # 1. 源库 DDL
    scur.execute(f"SHOW CREATE TABLE `{t}`")
    ddl = scur.fetchone()[1]
    # 2. 目标库重建（去掉 DDL 中的 AUTO_INCREMENT 起点? 保留, 避免 id 冲突）
    dcur.execute(f"DROP TABLE IF EXISTS `{t}`")
    dcur.execute(ddl)
    # 3. 搬数据
    scur.execute(f"SELECT * FROM `{t}`")
    rows = scur.fetchall()
    if not rows:
        print(f"  {t}: 0 rows (ddl ok)", flush=True)
        dc.commit()
        continue
    cols = [d[0] for d in scur.description]
    col_sql = ",".join(f"`{c}`" for c in cols)
    ph = ",".join(["%s"] * len(cols))
    dcur.executemany(f"INSERT INTO `{t}` ({col_sql}) VALUES ({ph})", rows)
    dc.commit()
    print(f"  {t}: {len(rows)} rows", flush=True)

scur.close(); dcur.close(); sc.close(); dc.close()
print("MIGRATE DONE", flush=True)
