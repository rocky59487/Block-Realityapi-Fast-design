@echo off
SET XLA_PYTHON_CLIENT_PREALLOCATE=false
SET XLA_PYTHON_CLIENT_MEM_FRACTION=0.85
SET NVIDIA_TF32_OVERRIDE=1
powershell -ExecutionPolicy Bypass -File "%~dp0start-trainer.ps1" %*
