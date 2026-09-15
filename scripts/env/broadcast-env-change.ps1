# broadcast-env-change.ps1 - Tell Windows that environment variables changed.
#
# Usage (must be run once after editing user/machine environment variables):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/env/broadcast-env-change.ps1
#
# WHY THIS IS NEEDED:
#   A new process inherits its parent's environment block - it does NOT re-read the
#   registry. On Windows, the Start menu, the taskbar and the desktop are all hosted
#   by explorer.exe, so anything you launch from them (including IntelliJ IDEA)
#   inherits explorer.exe's cached copy.
#
#   `setx` broadcasts WM_SETTINGCHANGE for you, which is why people think setx "just
#   works" and direct registry writes "don't". Writing the registry with `reg add` or
#   PowerShell's New-ItemProperty does NOT broadcast.
#
#   Note that this only refreshes already-running shell hosts. Processes that are
#   already open (your current terminal, an open IntelliJ) keep their stale block -
#   you must restart those individually.

Add-Type -Namespace Win32 -Name NativeMethods -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
public static extern IntPtr SendMessageTimeout(
    IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
    uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
'@

# HWND_BROADCAST = 0xFFFF, WM_SETTINGCHANGE = 0x001A, SMTO_ABORTIFHUNG = 0x0002
# lParam must be the literal string "Environment" for env var changes.
$result = [UIntPtr]::Zero
$ok = [Win32.NativeMethods]::SendMessageTimeout(
    [IntPtr]0xFFFF, 0x1A, [UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$result)

if ($ok -ne [IntPtr]::Zero) {
    Write-Output "[PASS] WM_SETTINGCHANGE broadcast sent - Explorer and other shell hosts will reload the environment."
} else {
    Write-Output "[WARN] Broadcast did not complete (some windows may be hung)."
    Write-Output "       Fallback: log off and log back on. That always works."
}
Write-Output ""
Write-Output "Already-open terminals and IDEs still hold the old environment - restart them."
