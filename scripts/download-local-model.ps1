param(
    [ValidateSet("tiny", "base", "small")]
    [string] $Model = "base"
)

$ErrorActionPreference = "Stop"

$models = @{
    tiny  = @{ File = "ggml-tiny.bin";  Url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin" }
    base  = @{ File = "ggml-base.bin";  Url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin" }
    small = @{ File = "ggml-small.bin"; Url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin" }
}

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root "models"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$selected = $models[$Model]
$outFile = Join-Path $outDir $selected.File

Write-Host "Downloading $($selected.File)"
Invoke-WebRequest -Uri $selected.Url -OutFile $outFile
Write-Host "Saved to $outFile"
