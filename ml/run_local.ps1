# Convenience runner for local Windows validation scripts using ml/.venv
$ErrorActionPreference = "Stop"
$repo = "c:\Users\wmc02\Desktop\git\Block-Realityapi-Fast-design"
$venv = "$repo\ml\.venv\Scripts\python.exe"
$env:PYTHONPATH = "$repo\ml\reborn-ml\src;$repo\ml\HYBR;$repo\ml\BR-NeXT;$repo\ml\brml"

param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("monument", "build_hybr", "stages")]
    [string]$Task
)

cd "$repo\ml\scripts\local"

switch ($Task) {
    "monument"    { & $venv monument_validation.py }
    "build_hybr"  { & $venv build_hybr_for_minecraft.py }
    "stages"      { & $venv test_all_stages.py }
}
