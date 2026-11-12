$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows) -or
    [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'This Coding Agent installer requires Windows x64.'
}

$root = Join-Path $env:LOCALAPPDATA 'HaifaAgent'
$bin = Join-Path $root 'bin'
$packages = Join-Path $root 'packages'
$stage = Join-Path $root ('.install-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $packages, $stage | Out-Null

try {
    Write-Host 'Downloading Coding Agent @TAG@ for Windows x64...'
    $archive = Join-Path $stage '@ARCHIVE@'
    Invoke-WebRequest -UseBasicParsing -Uri '@DOWNLOAD@' -OutFile $archive
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne '@SHA256@') {
        throw 'Coding Agent archive SHA-256 mismatch.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $stage
    $package = Join-Path $packages '@PACKAGE_ID@'
    if (-not [IO.Directory]::Exists($package)) {
        try {
            [IO.Directory]::Move((Join-Path $stage 'haifa-coding-agent-@TAG@'), $package)
        } catch [IO.IOException] {
            if (-not [IO.Directory]::Exists($package)) { throw }
        }
    }
    & (Join-Path $package 'jre/bin/java.exe') -cp (Join-Path $package 'lib/*') `
        io.haifa.agent.cli.distribution.InstallCodingAgent $package $bin
    if ($LASTEXITCODE -ne 0) { throw 'Coding Agent installation failed.' }

    # Preserve variable references and value type in the existing user PATH.
    $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
    try {
        $userPath = $key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        $expanded = [Environment]::ExpandEnvironmentVariables($userPath)
        if (($expanded -split ';').TrimEnd('\') -notcontains $bin) {
            $pathKind = if ($key.GetValueNames() -contains 'Path') { $key.GetValueKind('Path') } else { 'ExpandString' }
            $newPath = ($bin + ';' + $userPath).TrimEnd(';')
            $key.SetValue('Path', $newPath, $pathKind)
            # Notify Explorer and other top-level windows that the user environment changed.
            if (-not ('Haifa.InstallEnvironment' -as [type])) {
                Add-Type -Name InstallEnvironment -Namespace Haifa -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("user32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
public static extern System.IntPtr SendMessageTimeout(System.IntPtr window, uint message,
    System.IntPtr wParam, string lParam, uint flags, uint timeout, out System.IntPtr result);
'@
            }
            $result = [IntPtr]::Zero
            [Haifa.InstallEnvironment]::SendMessageTimeout([IntPtr]0xffff, 0x1a, [IntPtr]::Zero,
                'Environment', 2, 200, [ref]$result) | Out-Null
        }
    } finally {
        $key.Dispose()
    }
    if (($env:Path -split ';')[0] -ne $bin) {
        $env:Path = $bin + ';' + $env:Path
    }
    Write-Host 'Installed Coding Agent @TAG@. Run haifa from your project directory.'
} finally {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
