<#
.SYNOPSIS
    AI R Voice 배치 파이프라인을 Windows 작업 스케줄러에 매일 실행 태스크로 등록한다.

.DESCRIPTION
    프로젝트 venv의 python.exe 절대경로로 `python -m airvoice.main` 을 지정한 시각
    (기본 02:00)에 매일 실행하도록 "airvoice-nightly" 작업을 등록한다.
    이미 등록되어 있으면 설정을 갱신한다(멱등). 작업 자체가 실패하면(예: 크래시)
    1시간 뒤 자동으로 재시도하도록 설정한다.

.PARAMETER ProjectRoot
    프로젝트 루트 경로. 기본값: 이 스크립트가 위치한 scripts/ 폴더의 부모 폴더.
    이 경로 아래에 `.venv\Scripts\python.exe` 가 있어야 한다.

.PARAMETER Time
    매일 실행 시각 ("HH:mm", 24시간제). 기본값: "02:00".

.PARAMETER Unregister
    지정하면 등록된 "airvoice-nightly" 태스크를 제거하고 종료한다.

.EXAMPLE
    .\scripts\register_task.ps1
    기본 경로(../)와 기본 시각(02:00)으로 태스크를 등록(또는 갱신)한다.

.EXAMPLE
    .\scripts\register_task.ps1 -ProjectRoot "D:\Project\airvoice" -Time "03:30"
    지정한 프로젝트 루트/시각으로 태스크를 등록한다.

.EXAMPLE
    .\scripts\register_task.ps1 -Unregister
    등록된 태스크를 제거한다.

.NOTES
    작업 스케줄러 등록/수정은 관리자 권한 PowerShell에서 실행해야 할 수 있다.
#>

[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$Time = "02:00",
    [switch]$Unregister
)

# @TASK P5-T5.3 - 스케줄러 등록 & 운영 문서
# @SPEC docs/planning/06-tasks.md#P5-T5.3

$ErrorActionPreference = "Stop"

$TaskName = "airvoice-nightly"
$LegacyTaskName = "thinktank-nightly"

if ($Unregister) {
    $existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existingTask) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "작업 스케줄러에서 '$TaskName' 태스크를 제거했습니다."
    }
    else {
        Write-Host "'$TaskName' 태스크가 존재하지 않습니다. 제거할 것이 없습니다."
    }
    return
}

$legacyTask = Get-ScheduledTask -TaskName $LegacyTaskName -ErrorAction SilentlyContinue
if ($legacyTask) {
    Write-Warning "이전 '$LegacyTaskName' 태스크가 남아 있어 중복 실행될 수 있습니다. 새 태스크 검증 후 'Unregister-ScheduledTask -TaskName $LegacyTaskName -Confirm:`$false'로 제거하세요."
}

$ProjectRoot = (Resolve-Path -Path $ProjectRoot).Path
$PythonExe = Join-Path -Path $ProjectRoot -ChildPath ".venv\Scripts\python.exe"

if (-not (Test-Path -Path $PythonExe)) {
    throw "venv python을 찾을 수 없습니다: $PythonExe (먼저 프로젝트 루트에서 'uv venv' 로 가상환경을 생성하세요.)"
}

$taskAlreadyExists = [bool](Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)

$Launcher = Join-Path -Path $ProjectRoot -ChildPath "scripts\run_pipeline.cmd"
if (-not (Test-Path -Path $Launcher)) {
    throw "실행 래퍼를 찾을 수 없습니다: $Launcher"
}

# 래퍼를 거치는 이유: 작업 스케줄러는 stdout/stderr 를 버린다. 로그가 없으면
# 야간 실패가 며칠 뒤 노트가 안 쌓이는 걸로만 드러난다.
$Action = New-ScheduledTaskAction `
    -Execute $Launcher `
    -WorkingDirectory $ProjectRoot

$Trigger = New-ScheduledTaskTrigger -Daily -At $Time

# 실패 시 1시간 뒤 재시도(최대 3회), 예정 시각에 PC가 꺼져 있었다면 켜졌을 때 바로 실행(catch-up).
# 배터리 옵션이 없으면 랩탑에서 새벽에 전원이 빠져 있을 때 아예 실행되지 않거나
# 실행 중 중단된다(작업 스케줄러 기본값이 그렇다). STT 는 한 시간 넘게 걸릴 수 있어
# 실행 시간 제한도 넉넉히 둔다(기본 3일이지만 명시해 둔다).
$Settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Hours 6) `
    -MultipleInstances IgnoreNew `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Hours 1) `
    -StartWhenAvailable

# S4U(로그오프 상태에서도 실행)는 등록에 관리자 권한이 필요하다. Interactive 는
# 권한 없이 등록되지만 로그온 상태에서만 실행된다 — 화면이 잠겨 있어도 세션이
# 살아 있으면 실행되므로 개인 PC 에서는 이걸로 충분하다. 로그아웃한 채 밤을
# 넘긴다면 StartWhenAvailable 로 다음에 켤 때 밀린 실행이 따라잡는다.
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
    -Description "AI R Voice 야간 배치 파이프라인 (ingest -> vad -> transcribe -> extract -> organize)" `
    -Force `
    | Out-Null

# 등록이 실제로 됐는지 확인한다. Register-ScheduledTask 는 권한 부족 등으로
# 실패해도 스크립트를 멈추지 않는 경우가 있어, 확인 없이 성공 메시지를 내면
# 등록된 줄 알고 넘어가게 된다.
if (-not (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)) {
    throw "'$TaskName' 등록에 실패했습니다. 관리자 권한 PowerShell 에서 다시 시도하세요."
}

if ($taskAlreadyExists) {
    Write-Host "기존 '$TaskName' 태스크를 갱신했습니다."
}
else {
    Write-Host "'$TaskName' 태스크를 새로 등록했습니다."
}

Write-Host "실행 프로그램: $Launcher (python -m airvoice.main)"
Write-Host "로그: $env:USERPROFILE\.airvoice\pipeline.log"
Write-Host "작업 디렉터리: $ProjectRoot"
Write-Host "실행 시각: 매일 $Time (실패 시 1시간 간격 최대 3회 재시도)"
