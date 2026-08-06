[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$requiredPlatforms = @(
    "linux/amd64"
    "linux/arm64"
)

$images = @(
    "timescale/timescaledb:2.28.3-pg15"
    "eclipse-temurin:21-jdk-jammy"
    "eclipse-temurin:21-jre-jammy"
    "node:24.13.0-alpine3.23"
    "otel/opentelemetry-collector-contrib:0.157.0"
)

$results = @()

& docker buildx version | Out-Null

if ($LASTEXITCODE -ne 0) {
    throw "Docker Buildx is not available."
}

foreach ($image in $images) {
    Write-Host ""
    Write-Host "Inspecting $image"

    $inspectOutput = & docker buildx imagetools inspect `
        $image `
        2>&1

    $inspectExitCode = $LASTEXITCODE

    if ($inspectExitCode -ne 0) {
        throw (
            "Failed to inspect image: " +
            $image +
            [Environment]::NewLine +
            ($inspectOutput -join [Environment]::NewLine)
        )
    }

    $inspectText = (
        $inspectOutput |
        Out-String
    )

    $amd64Supported =
        $inspectText -match "linux/amd64"

    $arm64Supported =
        $inspectText -match "linux/arm64"

    $results += [PSCustomObject]@{
        Image = $image
        Amd64 = $amd64Supported
        Arm64 = $arm64Supported
        Compatible = (
            $amd64Supported -and
            $arm64Supported
        )
    }
}

Write-Host ""
Write-Host "Image platform compatibility"
Write-Host ""

$results |
    Format-Table `
        Image,
        Amd64,
        Arm64,
        Compatible `
        -AutoSize

$unsupportedImages = @(
    $results |
        Where-Object {
            -not $_.Compatible
        }
)

if ($unsupportedImages.Count -gt 0) {
    Write-Host ""
    Write-Host "Unsupported images:"

    $unsupportedImages |
        Format-Table `
            Image,
            Amd64,
            Arm64 `
            -AutoSize

    throw (
        "One or more images do not support " +
        "both linux/amd64 and linux/arm64."
    )
}

Write-Host ""
Write-Host (
    "All images support both " +
    "linux/amd64 and linux/arm64."
)