# verify-env.ps1 - Verify that the JDK environment variables are configured correctly.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/env/verify-env.ps1
#
# Why this script does NOT just run "java -version":
#   The shell you are currently sitting in was started BEFORE the environment
#   variables were written to the registry, so it holds a stale environment block.
#   Running java there proves nothing.
#
#   `Start-Process -UseNewEnvironment` looks like the fix, but it is NOT: it builds
#   the block on this machine WITHOUT the machine-level PATH, so even `where.exe`
#   becomes unresolvable. Do not use it to verify this.
#
#   What Windows actually does when you launch a new process is:
#       effective PATH = machine PATH + ";" + user PATH
#   with REG_EXPAND_SZ values expanded at that moment, so a user variable may
#   reference %JAVA_HOME% (itself a user variable). This script reproduces exactly
#   that algorithm, then runs java/javac under the reproduced PATH.

$machine  = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$user     = [Environment]::GetEnvironmentVariable('Path', 'User')
$javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')

Write-Output "=========== JDK environment verification ==========="
Write-Output ""
Write-Output "JAVA_HOME (User)  = [$javaHome]"
Write-Output ""
Write-Output "User PATH         = [$user]"
Write-Output ""

# Check 1: JAVA_HOME points at a real JDK directory.
$javaExe = $null
if ($javaHome) { $javaExe = Join-Path (Join-Path $javaHome 'bin') 'java.exe' }

if ($javaExe -and (Test-Path $javaExe)) {
    Write-Output "[PASS] java.exe exists at: $javaExe"
} else {
    Write-Output "[FAIL] java.exe NOT found under JAVA_HOME. Expected: $javaExe"
}

# Check 2: does the user PATH actually contain a java bin entry?
if ($user -match 'JAVA_HOME') {
    Write-Output "[INFO] User PATH references JAVA_HOME (REG_EXPAND_SZ will expand it at process launch)"
} else {
    Write-Output "[WARN] User PATH does not reference JAVA_HOME"
}

# Check 3: reproduce the effective PATH and run java/javac under it.
#
# GOTCHA: [Environment]::GetEnvironmentVariable returns the RAW registry string for
# REG_EXPAND_SZ values - it does NOT expand %VAR%. So "$machine + ';' + $user"
# would leave a literal, non-existent directory named "%JAVA_HOME%\bin" on the PATH
# and java would appear broken when it is actually fine.
#
# Windows expands these at process-launch time, resolving %VAR% against the
# machine variables plus the user variables. We reproduce that by seeding this
# process with only the variables the user PATH actually references.
$refs = [regex]::Matches($user, '%([^%]+)%') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
foreach ($name in $refs) {
    $v = [Environment]::GetEnvironmentVariable($name, 'User')
    if (-not $v) { $v = [Environment]::GetEnvironmentVariable($name, 'Machine') }
    if ($v) { Set-Item -Path "env:$name" -Value $v }
    Write-Output "[INFO] Seeded `$env:$name = $v  (referenced by user PATH)"
}

$userExpanded = [Environment]::ExpandEnvironmentVariables($user)
Write-Output ""
Write-Output "User PATH (expanded) = [$userExpanded]"

$effective = $machine + ';' + $userExpanded
$env:PATH = $effective

Write-Output ""
Write-Output "--- Which java.exe wins on the effective PATH ---"
$cmd = Get-Command java -ErrorAction SilentlyContinue
if ($cmd) {
    Write-Output "[PASS] resolved to: $($cmd.Source)"
} else {
    Write-Output "[FAIL] 'java' is not resolvable on the effective PATH"
}

Write-Output ""
Write-Output "--- java -version ---"
try { & java -version 2>&1 | ForEach-Object { Write-Output "  $_" } }
catch { Write-Output "  [FAIL] $_" }

Write-Output ""
Write-Output "--- javac -version ---"
try { & javac -version 2>&1 | ForEach-Object { Write-Output "  $_" } }
catch { Write-Output "  [FAIL] $_" }

Write-Output ""
Write-Output "==================================================="
