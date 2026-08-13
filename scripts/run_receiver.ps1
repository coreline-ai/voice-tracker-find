# AI R Voice LAN 수신기 기동 (모드1: 같은 WiFi 에서 폰 -> PC 직접 전송)
#
# 기본값은 .env 를 그대로 따른다(INGEST_DIR 등). 토큰만 .env 밖의 파일에서
# 읽어 쓴다 — 없으면 만들어 저장하고 이후 재사용한다.
#
# 사용법:
#   .\scripts\run_receiver.ps1                          # 평소 (수집 경로 = .env)
#   .\scripts\run_receiver.ps1 -IngestDir D:/airvoice-lan   # 격리 테스트
#   .\scripts\run_receiver.ps1 -Port 9000
#
# -IngestDir 을 주면 그 폴더로만 받는다. 실수로 실전 수집 경로를 오염시키지
# 않고 검증할 때 쓴다.

param(
    [string]$IngestDir = "",
    [int]$Port = 8765,
    [string]$BindAddress = "",
    [switch]$Tls,
    [string]$LogFile = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

# --- 토큰: 없으면 생성해 저장하고 이후 재사용 (.env 를 쓰지 않는다) ---
$tokenFile = Join-Path $HOME ".airvoice\receiver-token.txt"
if (-not (Test-Path $tokenFile)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $tokenFile) | Out-Null
    # RandomNumberGenerator::Fill 은 .NET Core 전용이라 PS 5.1 에서 없다.
    # Create().GetBytes() 는 5.1 / 7 양쪽에서 동작한다.
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $generated = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    Set-Content -Path $tokenFile -Value $generated -NoNewline
    Write-Host "새 토큰을 만들었습니다 -> $tokenFile" -ForegroundColor Yellow
}
$token = (Get-Content $tokenFile -Raw).Trim()

# --- 폰이 접속할 주소 탐지 (Tailscale/WSL 가상 어댑터 제외) ---
function Get-LanAddress {
    $lan = Get-NetIPConfiguration |
        Where-Object {
            $_.IPv4DefaultGateway -and
            $_.NetAdapter.Status -eq 'Up' -and
            $_.InterfaceAlias -notmatch 'Tailscale|WSL|vEthernet'
        } | Select-Object -First 1
    if ($lan) { $lan.IPv4Address.IPAddress } else { $null }
}

if (-not $BindAddress) {
    $found = Get-LanAddress
    $BindAddress = if ($found) { $found } else { "0.0.0.0" }
}

# 0.0.0.0 에 바인딩할 때(자동 시작 서비스)는 그 주소를 안내에 쓸 수 없다.
# 실제로 폰이 접속할 IP 를 따로 찾아 보여준다.
$displayAddress = $BindAddress
if ($BindAddress -eq "0.0.0.0") {
    $found = Get-LanAddress
    if ($found) { $displayAddress = $found }
}

# 수집 경로: -IngestDir 을 준 경우에만 .env 를 덮어쓴다.
if ($IngestDir) {
    New-Item -ItemType Directory -Force -Path $IngestDir | Out-Null
    $env:INGEST_DIR = $IngestDir
    $shownDir = "$IngestDir  (임시 지정 - .env 무시)"
} else {
    $envLine = Select-String -Path "$repo\.env" -Pattern '^INGEST_DIR=' -ErrorAction SilentlyContinue
    $shownDir = if ($envLine) { ($envLine.Line -split '=', 2)[1].Trim() + "  (.env)" } else { "~/.airvoice/inbox  (기본값)" }
}

# TLS: -Tls 를 준 경우에만. 앱이 공개키 피닝을 지원한 뒤에 켤 것 —
# 먼저 켜면 지금 설치된 앱(평문 HTTP)이 못 붙는다.
$scheme = "http"
if ($Tls) {
    $certFile = Join-Path $HOME ".airvoice\receiver-cert.pem"
    # 핀은 stdout 으로 나온다. 로그는 stderr 라 버린다 — 합치면(2>&1)
    # ErrorActionPreference=Stop 이 NativeCommandError 로 잡아 죽인다.
    $pin = & "$repo\.venv\Scripts\python.exe" -m airvoice.receiver `
        --make-cert $certFile --host $BindAddress 2>$null |
        Select-String -Pattern 'sha256/\S+' | ForEach-Object { $_.Matches[0].Value }
    if (-not $pin) { throw "인증서 생성 실패 (cryptography 미설치? uv pip install cryptography)" }
    $env:RECEIVER_CERT = $certFile
    $scheme = "https"
}

# APK 배포: PC 가 폰에 같은 WiFi 로 직접 전달한다. OneDrive 는 파일 온디맨드로
# dehydrate 되면 서빙할 때 클라우드에서 끌어와 느려지므로 쓰지 않는다 — 항상
# 로컬 사본(~/.airvoice)을 둔다. 다른 경로를 쓰려면 RECEIVER_APK 를 직접 지정.
if (-not $env:RECEIVER_APK) {
    $apk = Join-Path $HOME ".airvoice\ai-r-voice.apk"
    if (Test-Path $apk) { $env:RECEIVER_APK = $apk }
}

Write-Host ""
Write-Host "=== 폰 설정(③ 설정 탭)에 입력할 값 ===" -ForegroundColor Cyan
Write-Host "  서버 주소 : ${scheme}://${displayAddress}:${Port}"
Write-Host "  토큰      : $token"
Write-Host "  유저 ID   : 아무 값이나 (예: me)"
if ($Tls) {
    Write-Host "  공개키 핀 : $pin" -ForegroundColor Green
    Write-Host "              (앱에 이 값을 넣는다. 폰에 설치할 인증서는 없음)" -ForegroundColor DarkGray
}
Write-Host ""
Write-Host "수집 폴더  : $shownDir"
if ($env:RECEIVER_APK) {
    Write-Host "APK 배포    : 토큰 URL은 사용하지 않습니다. USB 또는 인증된 V1 배포 절차를 사용하세요." -ForegroundColor Yellow
}
Write-Host "연결 확인  : 폰 브라우저로 ${scheme}://${displayAddress}:${Port}/health -> ok" -ForegroundColor DarkGray
Write-Host "방화벽     : 첫 실행 시 Windows 가 물어보면 '개인 네트워크' 허용" -ForegroundColor DarkGray
Write-Host "중지       : Ctrl+C"
Write-Host ""

$env:RECEIVER_TOKEN = $token
$env:RECEIVER_PORT = "$Port"

# 작업 스케줄러로 창 없이 돌 때는 출력이 사라지므로 로그로 남긴다. 캡처 경로가
# 이 프로세스 하나에 달려 있어서, 조용히 죽으면 원인을 찾을 방법이 없다.
if ($LogFile) {
    New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null
    # 수신기 로그는 stderr 로 나간다. ErrorActionPreference=Stop 인 채로 스트림을
    # 합치면(*>>) PowerShell 이 그 첫 줄을 NativeCommandError 로 승격시켜 프로세스를
    # 죽인다 — 즉 서버가 뜨자마자 종료된다. 이 호출 동안만 Continue 로 낮춘다.
    $ErrorActionPreference = "Continue"
    & "$repo\.venv\Scripts\python.exe" -m airvoice.receiver --host $BindAddress *>> $LogFile
} else {
    & "$repo\.venv\Scripts\python.exe" -m airvoice.receiver --host $BindAddress
}
