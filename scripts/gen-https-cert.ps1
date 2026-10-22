# ============================================================
# Phase 1：生成 HTTPS 自签证书（Windows PowerShell 运行）
# 用法：PowerShell -ExecutionPolicy Bypass -File scripts/gen-https-cert.ps1
# 产物：certs/keystore.p12（PKCS12 密钥库，application-https.yml 引用）
# 说明：本地开发/演示用自签证书；生产请改用 CA 签发证书
# ============================================================
$ErrorActionPreference = "Stop"

$certDir = Join-Path $PSScriptRoot "..\certs"
$keyStore = Join-Path $certDir "keystore.p12"
$password = "here16-mall"
$alias = "here16-mall"

if (-not (Test-Path $certDir)) { New-Item -ItemType Directory -Force $certDir | Out-Null }

# keytool 随 JDK 提供（Gradle 工具链 JDK 21 自带）
$keytool = "keytool"
if (-not (Get-Command $keytool -ErrorAction SilentlyContinue)) {
    throw "未找到 keytool，请确认 JDK 已加入 PATH（Gradle 工具链目录也可以）"
}

# 生成自签证书并导出为 PKCS12
& $keytool -genkeypair `
    -alias $alias `
    -keyalg RSA `
    -keysize 2048 `
    -validity 3650 `
    -storetype PKCS12 `
    -keystore $keyStore `
    -storepass $password `
    -keypass $password `
    -dname "CN=localhost, OU=here16, O=here16-Mall, L=Maanshan, ST=Anhui, C=CN" `
    -ext "SAN=dns:localhost,ip:127.0.0.1"

Write-Host "HTTPS 证书已生成: $keyStore"
Write-Host "启动方式: gradlew bootRun --args='--spring.profiles.active=https'"
Write-Host "访问地址: https://localhost:8443"
