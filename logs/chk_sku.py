import requests, json
BASE = "http://localhost:8080"
j = requests.get(BASE + "/api/products/255").json()
d = j.get("data", {})
skus = d.get("skus") or d.get("skuList") or []
print("code:", j.get("code"))
print("name:", d.get("name"))
print("skus:", json.dumps(skus[:5], ensure_ascii=False)[:400])
