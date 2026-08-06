[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet(
        "Up",
        "Down",
        "Restart",
        "Status",
        "Logs",
        "Config"
    )]
    [string]$Action = "Status"
)

$ErrorActionPreference = "Stop"

$repositoryRoot = (
    Resolve-Path (
        Join-Path $PSScriptRoot "..\.."
    )
).Path

$baseComposeFile = Join-Path `
    $repositoryRoot `
    "docker-compose.yaml"

$appComposeFile = Join-Path `
    $repositoryRoot `
    "docker-compose.app.yaml"

$requiredSecretFiles = @(
    ".env"
    "otel-collector.env"
    "frontend.env"
)

$healthContainers = @(
    "aerotrace-timescaledb"
    "aerotrace-backend"
    "aerotrace-frontend"
)

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $composeArguments = @(
        "-f"
        $baseComposeFile
        "-f"
        $appComposeFile
        "--profile"
        "app"
    )

    & docker compose `
        @composeArguments `
        @Arguments

    if ($LASTEXITCODE -ne 0) {
        throw (
            "Docker Compose command failed " +
            "with exit code $LASTEXITCODE."
        )
    }
}

function Assert-RequiredFiles {
    foreach ($relativePath in $requiredSecretFiles) {
        $fullPath = Join-Path `
            $repositoryRoot `
            $relativePath

        if (-not (Test-Path -LiteralPath $fullPath)) {
            throw (
                "Required local environment file " +
                "was not found: $fullPath"
            )
        }
    }
}

function Wait-ForHealthyContainer {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ContainerName,

        [int]$TimeoutSeconds = 120
    )

    $deadline = (
        Get-Date
    ).AddSeconds($TimeoutSeconds)

    while ((Get-Date) -lt $deadline) {
        $status = & docker inspect `
            $ContainerName `
            --format `
            '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' `
            2>$null

        if (
            $LASTEXITCODE -eq 0 -and
            $null -ne $status
        ) {
            $normalizedStatus =
                $status.ToString().Trim()

            if ($normalizedStatus -eq "healthy") {
                Write-Host (
                    "$ContainerName is healthy."
                )

                return
            }

            if (
                $normalizedStatus -eq "exited" -or
                $normalizedStatus -eq "dead"
            ) {
                throw (
                    "$ContainerName entered " +
                    "state: $normalizedStatus"
                )
            }
        }

        Start-Sleep -Seconds 2
    }

    throw (
        "Timed out waiting for " +
        "$ContainerName to become healthy."
    )
}

function Start-IntegratedRuntime {
    Assert-RequiredFiles

    Write-Host "Validating Compose configuration."

    Invoke-Compose `
        -Arguments @(
            "config"
            "--quiet"
        )

    Write-Host "Building and starting AeroTrace."

    Invoke-Compose `
        -Arguments @(
            "up"
            "-d"
            "--build"
            "--remove-orphans"
        )

    foreach ($containerName in $healthContainers) {
        Wait-ForHealthyContainer `
            -ContainerName $containerName
    }

    Write-Host ""
    Write-Host "AeroTrace integrated runtime is ready."
    Write-Host "Dashboard: http://localhost:3000"
    Write-Host "Backend:   http://localhost:8080"
    Write-Host "OTLP HTTP: http://localhost:4318"
    Write-Host "Metrics:   http://localhost:8888/metrics"

    Write-Host ""

    Invoke-Compose `
        -Arguments @(
            "ps"
            "-a"
        )
}

function Stop-IntegratedRuntime {
    Write-Host (
        "Removing AeroTrace containers " +
        "without deleting named volumes."
    )

    Invoke-Compose `
        -Arguments @(
            "down"
            "--remove-orphans"
        )

    Write-Host ""
    Write-Host "Named volumes were preserved."
}

Set-Location -LiteralPath $repositoryRoot

switch ($Action) {
    "Up" {
        Start-IntegratedRuntime
    }

    "Down" {
        Stop-IntegratedRuntime
    }

    "Restart" {
        Stop-IntegratedRuntime
        Start-IntegratedRuntime
    }

    "Status" {
        Invoke-Compose `
            -Arguments @(
                "ps"
                "-a"
            )
    }

    "Logs" {
        Invoke-Compose `
            -Arguments @(
                "logs"
                "--tail"
                "200"
                "--follow"
            )
    }

    "Config" {
        Invoke-Compose `
            -Arguments @(
                "config"
                "--quiet"
            )

        Write-Host (
            "Compose configuration is valid."
        )
    }
}