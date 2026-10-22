import subprocess
docker = r"C:\Users\liguanting\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
# 清掉所有验证码键（点图片刷新后只剩最新一张）
out = subprocess.run([docker, "exec", "redis", "redis-cli", "--scan", "--pattern", "captcha:*"],
                     capture_output=True, text=True, timeout=10)
keys = [k for k in (out.stdout or "").splitlines() if k]
for k in keys:
    subprocess.run([docker, "exec", "redis", "redis-cli", "DEL", k], capture_output=True, text=True, timeout=10)
print("DELETED:", keys)
