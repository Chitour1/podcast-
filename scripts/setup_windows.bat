@echo off
setlocal
cd /d %~dp0\..
where python >nul 2>nul || (echo Python 3.11 is required.& pause & exit /b 1)
where ffmpeg >nul 2>nul || (echo FFmpeg is required in PATH.& pause & exit /b 1)
if not exist .venv python -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements.txt
python -c "from df.enhance import init_df; init_df('DeepFilterNet3', log_file=None); print('DeepFilterNet3 ready')"
echo Setup completed. The engine can now run offline.
pause
