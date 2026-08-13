<#
.SYNOPSIS
    AI R Voice LAN 수신기를 로그온 시 자동 시작하도록 작업 스케줄러에 등록한다.

.DESCRIPTION
    Syncthing 을 제거한 뒤로 수신기가 유일한 캡처 경로다. 터미널 창을 띄워두는
    방식은 창을 닫거나 재부팅하면 끊기므로, 로그온 시 창 없이 자동 시작하도록
    "airvoice-receiver" 작업을 등록한다. 이미 있으면 갱신하므로 여러 번 실행해도 안전하다.

    파이프라인 태스크(register_task.ps1)와 다른 점:
    - 상주 서비스라 실행 시간 제한을 없앤다(기본 3일이면 그때 죽는다).
    - 랩탑이므로 배터리에서도 시작/유지되게 한다(기본값은 배터리에서 중지).
    - 창이 없어 출력이 사라지므로 로그 파일로 남긴다.
    - 0.0.0.0 에 바인딩한다. 로그온 시점에 네트워크가 아직 안 올라왔거나 DHCP 로
      IP 가 바뀌어도 동작해야 하므로 특정 IP 를 고르지 않는다. LAN 밖 접근은
      방화벽 규칙(RemoteAddress 192.168.0.0/24)이 막는다.

.PARAMETER ProjectRoot
    프로젝트 루트 경로. 기본값: 이 스크립트가 있는 scripts/ 의 부모.

.PARAMETER Port
    수신기 포트. 기본 8765. 방화벽 규칙과 폰 설정의 포트가 같아야 한다.

.PARAMETER LogFile
    수신기 로그 경로. 기본 ~/.airvoice/receiver.log

.PARAMETER Unregister
    등록된 작업을 제거하고 종료한다.

.EXAMPLE
    .\scripts\register_receiver_task.ps1

.EXAMPLE
    .\scripts\register_receiver_task.ps1 -Unregister

.NOTES
    등록/수정은 관리자 권한 PowerShell이 필요할 수 있다.
#>

[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [int]$Port = 8765,
    [string]$LogFile = (Join-Path $HOME ".airvoice\receiver.log"),
    [switch]$Unregister
)

$ErrorActionPreference = "Stop"

$TaskName = "airvoice-receiver"
$LegacyTaskName = "thinktank-receiver"

if ($Unregister) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "작업 스케줄러에서 '$TaskName' 을 제거했습니다."
        Write-Host "이제 수신기는 자동 시작되지 않습니다 — 직접 run_receiver.ps1 을 실행하세요."
    }
    else {
        Write-Host "'$TaskName' 이 존재하지 않습니다. 제거할 것이 없습니다."
    }
    return
}

$legacyTask = Get-ScheduledTask -TaskName $LegacyTaskName -ErrorAction SilentlyContinue
if ($legacyTask) {
    Write-Warning "이전 '$LegacyTaskName' 태스크가 남아 있어 포트 충돌이 발생할 수 있습니다. 새 수신기 검증 후 'Unregister-ScheduledTask -TaskName $LegacyTaskName -Confirm:`$false'로 제거하세요."
}

$ProjectRoot = (Resolve-Path -Path $ProjectRoot).Path
$Launcher = Join-Path $ProjectRoot "scripts\run_receiver.ps1"
$PythonExe = Join-Path $ProjectRoot ".venv\Scripts\python.exe"

if (-not (Test-Path $PythonExe)) {
    throw "venv python을 찾을 수 없습니다: $PythonExe"
}
if (-not (Test-Path $Launcher)) {
    throw "런처를 찾을 수 없습니다: $Launcher"
}

$tokenFile = Join-Path $HOME ".airvoice\receiver-token.txt"
if (-not (Test-Path $tokenFile)) {
    Write-Host "토큰이 아직 없습니다. 첫 실행 때 생성됩니다 -> $tokenFile" -ForegroundColor Yellow
}

$taskAlreadyExists = [bool](Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)

$Action = New-ScheduledTaskAction `
    -Execute "powershell.exe" `
    -Argument ("-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass " +
               "-File `"$Launcher`" -BindAddress 0.0.0.0 -Port $Port -LogFile `"$LogFile`"") `
    -WorkingDirectory $ProjectRoot

$Trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"

# 상주 서비스용 설정. ExecutionTimeLimit 0 = 무제한(기본 3일이면 그때 종료된다).
# 배터리 관련 두 옵션이 없으면 랩탑이 전원을 빼는 순간 수신기가 멈춘다.
$Settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew `
    -StartWhenAvailable

$Principal = New-ScheduledTaskPrincipal `
    -UserId "$env:USERDOMAIN\$env:USERNAME" `
    -LogonType Interactive `
    -RunLevel Limited

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $Action `
    -Trigger $Trigger `
    -Settings $Settings `
    -Principal $Principal `
    -Description "AI R Voice LAN 수신기 (폰 -> PC 녹음 업로드). 로그온 시 자동 시작." `
    -Force `
    | Out-Null

if ($taskAlreadyExists) {
    Write-Host "기존 '$TaskName' 을 갱신했습니다."
}
else {
    Write-Host "'$TaskName' 을 새로 등록했습니다."
}

Write-Host ""
Write-Host "실행     : $Launcher -BindAddress 0.0.0.0 -Port $Port"
Write-Host "트리거   : 로그온 시 (창 없이)"
Write-Host "로그     : $LogFile"
Write-Host "지금 시작: Start-ScheduledTask -TaskName $TaskName"
Write-Host "해제     : .\scripts\register_receiver_task.ps1 -Unregister"
Write-Host ""
Write-Host "주의: 이미 터미널에서 수신기를 띄워뒀다면 포트가 겹칩니다." -ForegroundColor Yellow
Write-Host "      그 창을 Ctrl+C 로 먼저 닫으세요." -ForegroundColor Yellow
