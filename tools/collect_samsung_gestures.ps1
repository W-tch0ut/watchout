# 삼성 유니버설 제스처 수집 (Windows PowerShell)
#
# 워치 앱의 "삼성 제스처 수집" 화면이 센서 값을 기록하고(session__*.csv),
# 삼성 접근성 앱은 제스처를 인식할 때마다 워치 로그에 기록을 남긴다.
# 워치 앱은 다른 앱의 로그를 읽을 수 없어서(안드로이드 13+ 제한), 수집이 끝난 뒤 PC가 로그를 꺼낸다.
#
# 1) 수집 전:  powershell -ExecutionPolicy Bypass -File tools\collect_samsung_gestures.ps1 -Prepare
#    워치 로그 버퍼를 16MB로 키운다(기본 1MB, 몇 분치). 그러면 1시간쯤 지난 기록도 남는다.
#    워치를 다시 켜면 원래대로 돌아가므로, 수집하기 전에 한 번씩 실행한다.
# 2) 워치에서 수집한다. 이때는 PC가 연결돼 있지 않아도 된다.
# 3) 수집 후:  powershell -ExecutionPolicy Bypass -File tools\collect_samsung_gestures.ps1
#    워치 로그를 꺼내고, 가장 최근 세션 파일을 가져와 분석한다. (워치 화면을 켜 두어야 연결된다)
#
# 결과는 data\samsung\ 에 저장된다. data\ 는 Git에 올리지 않는다.

param(
    [switch]$Prepare,
    [string]$Adb = "C:\Android\Sdk\platform-tools\adb.exe",
    [string]$OutDir = "data\samsung"
)

$ErrorActionPreference = "Stop"
$remoteDir = "/sdcard/Android/data/com.watchout.wear/files/sessions"

if (-not (Test-Path $Adb)) { throw "adb를 찾을 수 없어요: $Adb" }

# 워치가 잠들어 연결이 끊겼으면 네트워크에서 워치 주소를 찾아 다시 연결해 본다.
if (-not (& $Adb devices | Select-String -Pattern "\tdevice$")) {
    $line = & $Adb mdns services | Select-String -Pattern "_adb-tls-connect" | Select-Object -First 1
    if ($line -and ($line.Line -match "(\d+\.\d+\.\d+\.\d+:\d+)")) { & $Adb connect $Matches[1] | Out-Null }
}
$connected = & $Adb devices | Select-String -Pattern "\tdevice$" | Select-Object -First 1
if (-not $connected) {
    throw "워치가 연결되지 않았어요. 워치 화면을 켜고(무선 디버깅 켜짐) 다시 실행하세요."
}
# 같은 워치가 두 주소로 잡히는 경우가 있어서, 첫 번째 것만 쓴다.
$serial = $connected.Line.Split("`t")[0]
function Invoke-Adb { & $Adb -s $serial @args }

if ($Prepare) {
    Invoke-Adb logcat -b main -G 16M
    Invoke-Adb logcat -b main -g
    Write-Host "준비됐어요. 워치에서 'WatchOut 제스처 → 삼성 제스처 수집'을 열고 제스처를 하세요."
    Write-Host "끝나면 -Prepare 없이 이 스크립트를 다시 실행하세요."
    return
}

New-Item -ItemType Directory -Force $OutDir | Out-Null
$latest = (Invoke-Adb shell ls -t "$remoteDir/*.csv" | Select-Object -First 1)
if (-not $latest) { throw "워치에 세션 파일이 없어요. '삼성 제스처 수집' 화면에서 기록했는지 확인하세요." }
$latest = $latest.Trim()
$name = Split-Path $latest -Leaf
$stem = [IO.Path]::GetFileNameWithoutExtension($name)

Invoke-Adb pull $latest $OutDir | Out-Null
$session = Join-Path $OutDir $name
$log = Join-Path $OutDir "$stem.logcat.txt"
Invoke-Adb logcat -d -b main -v epoch | Out-File -FilePath $log -Encoding utf8
Write-Host "가져왔습니다 → $session, $log"

python -m tools.samsung_gesture_labeler --session $session --log $log --out (Join-Path $OutDir "windows__$stem")
