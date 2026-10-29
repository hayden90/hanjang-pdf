# 창 이름에 $WinLike가 든 창에서, 컨트롤 종류 $Type·이름 $Name인 요소의 화면 사각형 "x y w h"를 출력한다.
# $Count를 주면 그 종류 요소 수만 출력한다.
param([string]$WinLike, [string]$Type, [string]$Name, [switch]$Count)
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$A = [System.Windows.Automation.AutomationElement]
$C = [System.Windows.Automation.PropertyCondition]
$ct = [System.Windows.Automation.ControlType]::$Type
$win = $A::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children,
  [System.Windows.Automation.Condition]::TrueCondition) | Where-Object { $_.Current.Name -like "*$WinLike*" } | Select-Object -First 1
if (-not $win) { 'nowin'; exit 1 }
$all = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, (New-Object $C($A::ControlTypeProperty, $ct)))
if ($Count) { @($all).Count; exit 0 }
$e = $all | Where-Object { $_.Current.Name -eq $Name } | Select-Object -First 1
if (-not $e) { 'noitem: ' + (($all | ForEach-Object { $_.Current.Name }) -join ','); exit 2 }
$r = $e.Current.BoundingRectangle
"{0} {1} {2} {3}" -f [int]$r.X, [int]$r.Y, [int]$r.Width, [int]$r.Height
