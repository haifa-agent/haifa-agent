param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CliArguments
)

$moduleRoot = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem -LiteralPath (Join-Path $moduleRoot "target") -Filter "haifa-agent-cli-*.jar" |
    Where-Object { $_.Name -notlike "original-*" } |
    Select-Object -First 1

if ($null -eq $jar) {
    throw "CLI jar is not built. Run .\mvnw.cmd -pl :haifa-agent-cli -am package first."
}

& java -jar $jar.FullName @CliArguments
exit $LASTEXITCODE
