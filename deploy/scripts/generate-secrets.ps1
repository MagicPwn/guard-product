$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$secrets = Join-Path $root "secrets"
New-Item -ItemType Directory -Force $secrets | Out-Null

function Write-Text([string]$name, [string]$value) {
    $path = Join-Path $secrets $name
    [System.IO.File]::WriteAllText($path, $value, [System.Text.UTF8Encoding]::new($false))
}

function Random-Bytes([int]$count) {
    $bytes = New-Object byte[] $count
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return $bytes
}

$opensslCommand = Get-Command openssl -ErrorAction SilentlyContinue
if ($null -ne $opensslCommand) {
    $openssl = $opensslCommand.Source
} else {
    $candidates = @(
        "C:\Program Files\Git\mingw64\bin\openssl.exe",
        "C:\Program Files\Git\usr\bin\openssl.exe"
    )
    $openssl = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($null -eq $openssl) {
        throw "openssl was not found. Install OpenSSL or add Git's OpenSSL to PATH."
    }
}
$temporary = Join-Path $secrets ".policy-key.tmp"
$der = Join-Path $secrets ".policy-public.der"
try {
    & $openssl ecparam -name prime256v1 -genkey -noout -out $temporary
    if ($LASTEXITCODE -ne 0) { throw "openssl ecparam failed" }
    & $openssl pkcs8 -topk8 -nocrypt -in $temporary -out (Join-Path $secrets "policy_private.pem")
    if ($LASTEXITCODE -ne 0) { throw "openssl pkcs8 failed" }
    & $openssl ec -in $temporary -pubout -out (Join-Path $secrets "policy_public.pem")
    if ($LASTEXITCODE -ne 0) { throw "openssl ec failed" }
    & $openssl pkey -pubin -in (Join-Path $secrets "policy_public.pem") -outform DER -out $der
    if ($LASTEXITCODE -ne 0) { throw "openssl pkey failed" }
    $publicBase64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($der))
    $keyId = (Get-FileHash -Algorithm SHA256 $der).Hash.ToLowerInvariant()
} finally {
    Remove-Item -LiteralPath $temporary, $der -Force -ErrorAction SilentlyContinue
}

Write-Text "postgres_password" ([Convert]::ToBase64String((Random-Bytes 32)))
Write-Text "jwt_secret" ([Convert]::ToBase64String((Random-Bytes 48)))
Write-Text "livekit_api_key" ("API" + [Convert]::ToBase64String((Random-Bytes 18)).Replace("+", "A").Replace("/", "B"))
Write-Text "livekit_api_secret" ([Convert]::ToBase64String((Random-Bytes 32)))

Write-Host "Policy key id:     $keyId"
Write-Host "Policy public key: $publicBase64"
Write-Host ""
Write-Host "Use these only when building the APK/key pin:"
Write-Host "-PguardPolicyKeyId=$keyId"
Write-Host "-PguardPolicyPublicKey=$publicBase64"
