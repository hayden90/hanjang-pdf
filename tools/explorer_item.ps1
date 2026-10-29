# 탐색기 창(제목이 폴더 이름으로 시작)을 오른쪽으로 옮기고, 그 안의 파일 항목 화면 위치(실제 점)를 "x y"로 출력한다.
# -Close: 그 폴더 이름의 탐색기 창을 모두 닫기만 한다.
param([string]$Folder, [string]$Name, [switch]$Close)
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$A = [System.Windows.Automation.AutomationElement]
$C = [System.Windows.Automation.PropertyCondition]
$title = Split-Path $Folder -Leaf
function Find-Wins {
  $A::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children,
    (New-Object $C($A::ClassNameProperty, 'CabinetWClass'))) |
    Where-Object { $_.Current.Name -like "$title*" }
}
if ($Close) {
  foreach ($w in @(Find-Wins)) {
    try { $w.GetCurrentPattern([System.Windows.Automation.WindowPattern]::Pattern).Close() } catch {}
  }
  'closed'; exit 0
}
$win = $null
for ($i = 0; $i -lt 40 -and -not $win; $i++) {
  $win = @(Find-Wins) | Select-Object -First 1
  if (-not $win) { Start-Sleep -Milliseconds 300 }
}
if (-not $win) { 'nowin'; exit 1 }
try {
  $tp = $win.GetCurrentPattern([System.Windows.Automation.TransformPattern]::Pattern)
  $tp.Move(1150, 80); $tp.Resize(700, 600)
} catch {}
Start-Sleep -Milliseconds 800
$stem = [IO.Path]::GetFileNameWithoutExtension($Name)
$item = $null
for ($i = 0; $i -lt 20 -and -not $item; $i++) {
  $item = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants,
    (New-Object $C($A::ControlTypeProperty, [System.Windows.Automation.ControlType]::ListItem))) |
    Where-Object { $_.Current.Name -eq $Name -or $_.Current.Name -eq $stem } | Select-Object -First 1
  if (-not $item) { Start-Sleep -Milliseconds 300 }
}
if (-not $item) { 'noitem'; exit 2 }
$r = $item.Current.BoundingRectangle
"{0} {1}" -f [int]($r.X + 30), [int]($r.Y + $r.Height / 2)
