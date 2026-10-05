# Sync the key_* lines of the BMC5 instance's options.txt with keybinds.txt in this folder.
#   apply : keybinds.txt -> options.txt (other settings are kept, a backup is made first)
#   export: options.txt -> keybinds.txt
# Override the instance path with the BMC5_DIR environment variable.
param([Parameter(Mandatory)][ValidateSet('apply', 'export')][string]$Mode)
$ErrorActionPreference = 'Stop'

$instance = $env:BMC5_DIR
if (-not $instance) { $instance = Join-Path $env:USERPROFILE 'curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5' }
$options = Join-Path $instance 'options.txt'
$keybinds = Join-Path $PSScriptRoot 'keybinds.txt'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-Lines($path) { [IO.File]::ReadAllText($path, $utf8) -split "\r?\n" | Where-Object { $_ -ne '' } }
function Write-Lines($path, $lines) { [IO.File]::WriteAllText($path, (($lines -join "`r`n") + "`r`n"), $utf8) }

if (-not (Test-Path -LiteralPath $options)) { throw "options.txt not found: $options (run the game once, or set BMC5_DIR)" }

if ($Mode -eq 'export') {
    $keys = @(Read-Lines $options | Where-Object { $_ -like 'key_*' })
    Write-Lines $keybinds $keys
    Write-Host "Exported $($keys.Count) keybindings to $keybinds"
    exit 0
}

if (Get-Process -Name javaw, java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' }) {
    throw 'Minecraft is running. Close the game first, or it will overwrite options.txt on exit.'
}

$wanted = [ordered]@{}
foreach ($line in Read-Lines $keybinds) {
    $name = $line.Split(':')[0]
    if ($name -like 'key_*') { $wanted[$name] = $line }
}

$seen = @{}
$changed = 0
$out = foreach ($line in Read-Lines $options) {
    $name = $line.Split(':')[0]
    if ($wanted.Contains($name)) {
        $seen[$name] = $true
        if ($line -ne $wanted[$name]) { $changed++ }
        $wanted[$name]
    } else { $line }
}
$added = @($wanted.Keys | Where-Object { -not $seen[$_] } | ForEach-Object { $wanted[$_] })
$out = @($out) + $added

$backup = "$options.bak-$(Get-Date -Format yyyyMMdd-HHmmss)"
Copy-Item -LiteralPath $options -Destination $backup
Write-Lines $options $out
Write-Host "Applied keybindings: $changed changed, $($added.Count) added. Backup: $backup"
