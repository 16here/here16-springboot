# Phase2 全链路验证 Python 版（输出稳定）
# 链路: 登录 -> 浏览行为(异步落库) -> 缓存双删 -> 支付回调异步 -> 延迟关单
import json, subprocess, time, hashlib, sys
import requests

BASE = "http://localhost:8080"

def redis_get(key):
    r = subprocess.run(["docker", "exec", "redis", "redis-cli", "GET", key], capture_output=True, text=True)
    return r.stdout.strip()

def redis_keys(pattern):
    r = subprocess.run(["docker", "exec", "redis", "redis-cli", "KEYS", pattern], capture_output=True, text=True)
    return [l for l in r.stdout.splitlines() if l.strip()]

def redis_del(key):
    subprocess.run(["docker", "exec", "redis", "redis-cli", "DEL", key], capture_output=True, text=True)

def ok(j):
    return j.get("code") in (0, 200)

def get_captcha():
    r = requests.get(BASE + "/api/auth/captcha")
    j = r.json()
    if not ok(j):
        raise RuntimeError(f"captcha fail: {j}")
    return j["data"]["captchaId"]

def login(account, password):
    cid = get_captcha()
    code = redis_get(f"captcha:{cid}")
    print(f"  captchaId={cid} redisCode={code!r}", flush=True)
    r = requests.post(BASE + "/api/auth/login", json={"account": account, "password": password, "captchaId": cid, "captchaCode": code})
    j = r.json()
    print(f"  login resp code={j.get('code')} msg={j.get('message')}", flush=True)
    if not ok(j):
        raise RuntimeError(f"login fail: {j}")
    return j["data"]

def main():
    print("== Step0 登录 ==", flush=True)
    data = login("user6", "user6123456")
    token = data.get("token") or data.get("accessToken")
    uid = data.get("id") or (data.get("user") or {}).get("id")
    print(f"  uid={uid} tokenLen={len(token) if token else 0}", flush=True)
    H = {"Authorization": f"Bearer {token}"}

    print("== Step1 链路③ 浏览行为异步落库 ==", flush=True)
    # 记录当前 VIEW 计数
    before = redis_get("none")  # 占位
    r = requests.get(BASE + "/api/products/255", headers=H)
    print(f"  browse resp={r.status_code} name={r.json().get('data',{}).get('name')}", flush=True)
    time.sleep(4)
    print("  等待4s后查库 user_behaviors（用 python 查 DB，见下）", flush=True)

    print("== Step2 链路④ 缓存双删 ==", flush=True)
    r = requests.get(BASE + "/api/products?page=1&pageSize=5")
    print(f"  list resp={r.status_code}", flush=True)
    keys = redis_keys("*product*")
    print(f"  redis product keys: {keys}", flush=True)
    # 管理端改商品（触发双删）——需要 admin 登录
    adata = login("admin", "123456")
    atoken = adata.get("token") or adata.get("accessToken")
    AH = {"Authorization": f"Bearer {atoken}"}
    upd = {"name": "红枣蜜 散装500g", "price": 199.0, "categoryId": 2, "description": "双删验证", "status": 1, "sku": "P255-TEST"}
    r = requests.put(BASE + "/api/admin/products/255", json=upd, headers=AH)
    print(f"  admin update product resp={r.status_code} body={r.text[:120]}", flush=True)
    time.sleep(3)
    keys2 = redis_keys("*product*")
    print(f"  redis product keys after update: {keys2}", flush=True)
    detail_keys = [k for k in keys2 if "detail:255" in k or "list" in k]
    print(f"  双删结果: 残留缓存 key={detail_keys} （期望为空或已被删除）", flush=True)

    print("== Step3 链路② 支付回调异步 ==", flush=True)
    # 用订单列表找待付款订单
    r = requests.get(BASE + "/api/orders?page=1&pageSize=5", headers=H)
    o = r.json()
    print(f"  orders resp code={o.get('code')} total={o.get('data',{}).get('total')}", flush=True)
    if o.get("code") == 0 and o["data"].get("list"):
        print(f"  first order: id={o['data']['list'][0].get('id')} status={o['data']['list'][0].get('status')}", flush=True)

    print("== DONE Step0-3 ==", flush=True)

if __name__ == "__main__":
    main()
