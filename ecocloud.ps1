<#
.SYNOPSIS
    Root launcher forwarding to scripts/ecocloud.ps1.
#>
& "$PSScriptRoot\scripts\ecocloud.ps1" @args
exit $LASTEXITCODE
