import os
import time
import subprocess
import re

STATUS_FILE = "ml/experiments/HOURLY_STATUS.md"
AUTOPILOT_RAW = "ml/experiments/overnight_autopilot_raw.log"

def get_gpu_status():
    try:
        res = subprocess.check_output(["wsl", "-d", "Ubuntu-22.04", "bash", "-c", "nvidia-smi --query-gpu=utilization.gpu,memory.used,temp.gpu --format=csv,noheader,nounits"], text=True)
        util, mem, temp = res.strip().split(", ")
        return f"Util: {util}%, VRAM: {mem}MiB, Temp: {temp}C"
    except:
        return "N/A"

def get_latest_progress():
    if not os.path.exists(AUTOPILOT_RAW):
        return "Waiting for logs..."
    try:
        # Read last 50 lines
        with open(AUTOPILOT_RAW, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()[-50:]
        
        content = "".join(lines)
        stage_match = re.findall(r"Stage (\d):", content)
        step_match = re.findall(r"\[S(\d) step (\d+)/(\d+)\] loss=([\d\.]+)", content)
        
        current_stage = stage_match[-1] if stage_match else "Unknown"
        if step_match:
            s, step, total, loss = step_match[-1]
            return f"Stage {s}, Step {step}/{total}, Loss: {loss}"
        return f"Initializing Stage {current_stage}..."
    except:
        return "Parsing progress..."

def main():
    # Initial Header
    with open(STATUS_FILE, "w") as f:
        f.write("# 📊 Hourly Training Dashboard\n")
        f.write("*I will update this file every hour to keep you informed.*\n\n")
        f.write("| Timestamp | Progress | GPU Status |\n")
        f.write("| :--- | :--- | :--- |\n")

    while True:
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
        progress = get_latest_progress()
        gpu = get_gpu_status()
        
        with open(STATUS_FILE, "a") as f:
            f.write(f"| {timestamp} | {progress} | {gpu} |\n")
        
        # Check if autopilot is still running
        try:
            res = subprocess.check_output(["wsl", "-d", "Ubuntu-22.04", "bash", "-c", "ps aux | grep overnight_autopilot | grep -v grep"], text=True)
        except subprocess.CalledProcessError:
            with open(STATUS_FILE, "a") as f:
                f.write(f"\n**⚠️ WARNING: Autopilot process seems to have stopped at {timestamp}!**\n")
        
        time.sleep(3600)

if __name__ == "__main__":
    main()
