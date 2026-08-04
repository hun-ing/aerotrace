[CmdletBinding()]
param(
    [string]$RepositoryRoot = "D:\aerotrace"
)

$ErrorActionPreference = "Stop"

function Write-CommandResult {
    param(
        [Parameter(Mandatory = $true)]
        [string]$OutputFile,

        [Parameter(Mandatory = $true)]
        [scriptblock]$Command
    )

    try {
        $result = & $Command 2>&1

        if ($null -eq $result) {
            "(no output)" |
                Set-Content `
                    -LiteralPath $OutputFile `
                    -Encoding UTF8

            return
        }

        $result |
            Out-String -Width 300 |
            Set-Content `
                -LiteralPath $OutputFile `
                -Encoding UTF8
    }
    catch {
        @(
            "Command failed"
            ""
            $_.Exception.Message
        ) |
            Set-Content `
                -LiteralPath $OutputFile `
                -Encoding UTF8
    }
}

function Test-HttpEndpoint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url
    )

    try {
        $response = Invoke-WebRequest `
            -Uri $Url `
            -Method Get `
            -TimeoutSec 3 `
            -UseBasicParsing

        return [PSCustomObject]@{
            Url = $Url
            StatusCode = [int]$response.StatusCode
            Result = "reachable"
        }
    }
    catch {
        $statusCode = $null

        if (
            $null -ne $_.Exception.Response -and
            $null -ne $_.Exception.Response.StatusCode
        ) {
            $statusCode =
                [int]$_.Exception.Response.StatusCode
        }

        return [PSCustomObject]@{
            Url = $Url
            StatusCode = $statusCode
            Result = $_.Exception.Message
        }
    }
}

if (-not (Test-Path -LiteralPath $RepositoryRoot)) {
    throw "Repository path was not found: $RepositoryRoot"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

$outputDirectory = Join-Path `
    $env:TEMP `
    "AeroTrace-phase9-inventory-$timestamp"

New-Item `
    -ItemType Directory `
    -Force `
    -Path $outputDirectory |
    Out-Null

Set-Location -LiteralPath $RepositoryRoot

$excludedDirectoryPattern =
    "\\(\.git|\.gradle|\.next|node_modules|build|out|dist)\\"

$relevantFilePattern =
    "^(compose.*\.(yml|yaml)|docker-compose.*\.(yml|yaml)|otel.*\.(yml|yaml)|application.*\.(yml|yaml)|Dockerfile(\..*)?|\.env\.example|\.nvmrc)$"

$relevantFiles = @(
    Get-ChildItem `
        -LiteralPath $RepositoryRoot `
        -Recurse `
        -File |
        Where-Object {
            $_.FullName -notmatch $excludedDirectoryPattern -and
            $_.Name -match $relevantFilePattern
        } |
        Sort-Object FullName
)

$relevantFileOutput = @()

foreach ($file in $relevantFiles) {
    $relativePath = $file.FullName.Substring(
        $RepositoryRoot.Length
    )

    $relevantFileOutput +=
        $relativePath.TrimStart("\")
}

if ($relevantFileOutput.Count -eq 0) {
    $relevantFileOutput = @(
        "(no relevant files found)"
    )
}

$relevantFileOutput |
    Set-Content `
        -LiteralPath (
            Join-Path `
                $outputDirectory `
                "01-relevant-files.txt"
        ) `
        -Encoding UTF8

Write-CommandResult `
    -OutputFile (
        Join-Path `
            $outputDirectory `
            "02-git-status.txt"
    ) `
    -Command {
        git status --short
    }

$composeCandidates = @(
    $relevantFiles |
        Where-Object {
            $_.Name -match (
                "^(compose|docker-compose).*" +
                "\.(yml|yaml)$"
            )
        }
)

$composeFile = $composeCandidates |
    Sort-Object `
        @{
            Expression = {
                if (
                    $_.DirectoryName -eq
                    $RepositoryRoot
                ) {
                    0
                }
                else {
                    1
                }
            }
        },
        FullName |
    Select-Object -First 1

if ($null -eq $composeFile) {
    "(no compose file found)" |
        Set-Content `
            -LiteralPath (
                Join-Path `
                    $outputDirectory `
                    "03-compose-services.txt"
            ) `
            -Encoding UTF8

    "(no compose file found)" |
        Set-Content `
            -LiteralPath (
                Join-Path `
                    $outputDirectory `
                    "04-compose-images.txt"
            ) `
            -Encoding UTF8

    "(no compose file found)" |
        Set-Content `
            -LiteralPath (
                Join-Path `
                    $outputDirectory `
                    "05-compose-status.txt"
            ) `
            -Encoding UTF8
}
else {
    Write-CommandResult `
        -OutputFile (
            Join-Path `
                $outputDirectory `
                "03-compose-services.txt"
        ) `
        -Command {
            "Compose file: $($composeFile.FullName)"
            ""
            docker compose `
                -f $composeFile.FullName `
                config `
                --services
        }

    Write-CommandResult `
        -OutputFile (
            Join-Path `
                $outputDirectory `
                "04-compose-images.txt"
        ) `
        -Command {
            "Compose file: $($composeFile.FullName)"
            ""
            docker compose `
                -f $composeFile.FullName `
                config `
                --images
        }

    Write-CommandResult `
        -OutputFile (
            Join-Path `
                $outputDirectory `
                "05-compose-status.txt"
        ) `
        -Command {
            "Compose file: $($composeFile.FullName)"
            ""
            docker compose `
                -f $composeFile.FullName `
                ps `
                -a
        }
}

$versionOutput = @()

$versionCommands = @(
    @{
        Name = "Docker"
        Command = {
            docker version
        }
    }
    @{
        Name = "Docker Compose"
        Command = {
            docker compose version
        }
    }
    @{
        Name = "Java"
        Command = {
            java -version
        }
    }
    @{
        Name = "Node.js"
        Command = {
            node --version
        }
    }
    @{
        Name = "npm"
        Command = {
            npm --version
        }
    }
)

foreach ($versionCommand in $versionCommands) {
    $versionOutput +=
        "=== $($versionCommand.Name) ==="

    try {
        $commandResult =
            & $versionCommand.Command 2>&1

        if ($null -eq $commandResult) {
            $versionOutput += "(no output)"
        }
        else {
            foreach ($line in $commandResult) {
                $versionOutput +=
                    $line.ToString()
            }
        }
    }
    catch {
        $versionOutput +=
            "Command failed: $($_.Exception.Message)"
    }

    $versionOutput += ""
}

$versionOutput |
    Set-Content `
        -LiteralPath (
            Join-Path `
                $outputDirectory `
                "06-tool-versions.txt"
        ) `
        -Encoding UTF8

$trackedFiles = @(
    git ls-files
)

$configurationFilePattern =
    '(^|/)(compose.*\.(yml|yaml)|docker-compose.*\.(yml|yaml)|.*\.env\.example|\.env\.example|application.*\.(yml|yaml)|otel.*\.(yml|yaml))$'

$environmentVariableNames = @()

foreach ($relativePath in $trackedFiles) {
    if (
        $relativePath -notmatch
        $configurationFilePattern
    ) {
        continue
    }

    $fullPath = Join-Path `
        $RepositoryRoot `
        $relativePath

    if (-not (Test-Path -LiteralPath $fullPath)) {
        continue
    }

    $lines = Get-Content `
        -LiteralPath $fullPath

    foreach ($line in $lines) {
        $placeholderMatches =
            [regex]::Matches(
                $line,
                '\$\{([A-Z][A-Z0-9_]*)(?::-[^}]*)?\}'
            )

        foreach (
            $placeholderMatch
            in $placeholderMatches
        ) {
            $environmentVariableNames +=
                $placeholderMatch.Groups[1].Value
        }

        if (
            [System.IO.Path]::GetFileName(
                $fullPath
            ) -eq ".env.example"
        ) {
            $assignmentMatch =
                [regex]::Match(
                    $line,
                    '^\s*([A-Z][A-Z0-9_]*)\s*='
                )

            if ($assignmentMatch.Success) {
                $environmentVariableNames +=
                    $assignmentMatch.Groups[1].Value
            }
        }
    }
}

$environmentVariableNames =
    @(
        $environmentVariableNames |
            Sort-Object -Unique
    )

if ($environmentVariableNames.Count -eq 0) {
    $environmentVariableNames = @(
        "(no environment variable names found)"
    )
}

$environmentVariableNames |
    Set-Content `
        -LiteralPath (
            Join-Path `
                $outputDirectory `
                "07-environment-variable-names.txt"
        ) `
        -Encoding UTF8

$targetPorts = @(
    3000
    4317
    4318
    5432
    8080
    8888
)

try {
    $portStatus = @(
        Get-NetTCPConnection `
            -State Listen `
            -ErrorAction SilentlyContinue |
            Where-Object {
                $targetPorts -contains
                $_.LocalPort
            } |
            Select-Object `
                LocalAddress,
                LocalPort,
                OwningProcess |
            Sort-Object LocalPort
    )

    if ($portStatus.Count -eq 0) {
        "No target ports are listening." |
            Set-Content `
                -LiteralPath (
                    Join-Path `
                        $outputDirectory `
                        "08-listening-ports.txt"
                ) `
                -Encoding UTF8
    }
    else {
        $portStatus |
            Format-Table -AutoSize |
            Out-String -Width 300 |
            Set-Content `
                -LiteralPath (
                    Join-Path `
                        $outputDirectory `
                        "08-listening-ports.txt"
                ) `
                -Encoding UTF8
    }
}
catch {
    "Port query failed: $($_.Exception.Message)" |
        Set-Content `
            -LiteralPath (
                Join-Path `
                    $outputDirectory `
                    "08-listening-ports.txt"
            ) `
            -Encoding UTF8
}

$endpointResults = @()

$endpointResults +=
    Test-HttpEndpoint `
        -Url "http://localhost:8080/actuator/health"

$endpointResults +=
    Test-HttpEndpoint `
        -Url "http://localhost:3000"

$endpointResults +=
    Test-HttpEndpoint `
        -Url "http://localhost:8888/metrics"

$endpointResults |
    Format-Table -AutoSize |
    Out-String -Width 300 |
    Set-Content `
        -LiteralPath (
            Join-Path `
                $outputDirectory `
                "09-http-endpoints.txt"
        ) `
        -Encoding UTF8

@(
    "This inventory intentionally excludes:"
    ""
    "- frontend/.env.local contents"
    "- API key values"
    "- database password values"
    "- rendered Docker container environment values"
    "- full docker compose config output"
    ""
    "Only environment variable names are collected."
) |
    Set-Content `
        -LiteralPath (
            Join-Path `
                $outputDirectory `
                "10-security-note.txt"
        ) `
        -Encoding UTF8

$zipPath = "$outputDirectory.zip"

Compress-Archive `
    -Path (
        Join-Path `
            $outputDirectory `
            "*"
    ) `
    -DestinationPath $zipPath `
    -Force

Write-Host ""
Write-Host "Inventory completed."
Write-Host "Directory: $outputDirectory"
Write-Host "ZIP:       $zipPath"