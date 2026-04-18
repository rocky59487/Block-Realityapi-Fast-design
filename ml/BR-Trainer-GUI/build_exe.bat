@echo off
echo ====================================================
echo BR-NeXT Studio Pro Builder
echo ====================================================

echo 1. Checking requirements...
python -m pip install pyinstaller matplotlib numpy

echo 2. Building executable...
python -m PyInstaller --noconfirm --onefile --windowed --name "BR-Studio" gui_main.py

echo 3. Build complete!
echo ====================================================
echo You can find the executable at: dist\BR-Studio.exe
echo ====================================================
