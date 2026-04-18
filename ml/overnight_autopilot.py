import os
import time
import subprocess
import json
import re
import traceback
from datetime import datetime
import shutil

LOG_DIR = "ml/experiments/logs"
ARCHIVE_DIR = "ml/experiments/archive"
REPORT_FILE = "ml/experiments/OVERNIGHT_REPORT.md"
STATE_FILE = "ml/experiments/autopilot_state.json"

os.makedirs(LOG_DIR, exist_ok=True)
os.makedirs(ARCHIVE_DIR, exist_ok=True)

def log(msg, level="INFO"):
    timestamp = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    log_line = f"[{timestamp}] [{level}] [AutoPilot] {msg}"
    print(log_line, flush=True)
    with open(os.path.join(LOG_DIR, "overnight_autopilot.log"), "a", encoding="utf-8") as f:
        f.write(log_line + "\n")

def load_state():
    if os.path.exists(STATE_FILE):
        try:
            with open(STATE_FILE, "r") as f:
                return json.load(f)
        except Exception as e:
            log(f"Failed to load state: {e}", "ERROR")
    return {"completed_stages": [], "retries": {}}

def save_state(state):
    with open(STATE_FILE, "w") as f:
        json.dump(state, f, indent=4)

def archive_model(model_path, stage_name):
    if not os.path.exists(model_path):
        return None
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    ext = os.path.splitext(model_path)[1]
    archive_name = f"{stage_name}_{timestamp}{ext}"
    archive_path = os.path.join(ARCHIVE_DIR, archive_name)
    shutil.copy2(model_path, archive_path)
    log(f"Archived model {model_path} to {archive_path}")
    return archive_path

def fallback_export(checkpoint_path, output_onnx):
    log(f"Attempting custom fallback export for {checkpoint_path}...", "WARNING")
    # Using the manual_onnx exporter in brnext
    cmd = f"python3 -m brnext.export.manual_onnx --ckpt {checkpoint_path} --out {output_onnx}"
    code, out = run_cmd(cmd)
    if code == 0 and os.path.exists(output_onnx):
        log(f"Fallback export successful: {output_onnx}", "SUCCESS")
        return True
    log(f"Fallback export failed: {out}", "ERROR")
    return False

def run_cmd(cmd, env=None, log_file="cmd_raw.log"):
    log(f"Running: {cmd}")
    env_vars = os.environ.copy()
    if env:
        env_vars.update(env)
    
    # Ensure PYTHONPATH is set
    if "PYTHONPATH" not in env_vars:
        env_vars["PYTHONPATH"] = "ml/BR-NeXT:ml/HYBR:ml/brml"
    else:
        env_vars["PYTHONPATH"] = f"ml/BR-NeXT:ml/HYBR:ml/brml:{env_vars['PYTHONPATH']}"
        
    full_cmd = f"cd /mnt/c/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design-1 && source ml/.venv_wsl/bin/activate && {cmd}"
    
    proc = subprocess.Popen(["bash", "-c", full_cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env_vars)
    output = []
    log_path = os.path.join(LOG_DIR, log_file)
    with open(log_path, "a", encoding="utf-8") as f:
        f.write(f"\n--- CMD START: {datetime.now()} ---\n{cmd}\n")
        for line in proc.stdout:
            output.append(line)
            f.write(line)
            f.flush()
        f.write(f"--- CMD END ---\n")
    proc.wait()
    return proc.returncode, "".join(output)

def run_with_retry(stage_name, cmd, max_retries=3, fallback_checkpoint=None, expected_output=None):
    state = load_state()
    if stage_name in state["completed_stages"]:
        log(f"Stage '{stage_name}' already completed. Skipping.")
        return True, "Skipped (Already completed)"

    retries = state["retries"].get(stage_name, 0)
    
    while retries < max_retries:
        log(f"Starting stage '{stage_name}' (Attempt {retries + 1}/{max_retries})")
        
        try:
            code, out = run_cmd(cmd, log_file=f"{stage_name.replace(' ', '_')}.log")
            
            # Check if expected output exists (if provided)
            success = code == 0
            if expected_output and not os.path.exists(expected_output):
                success = False
                log(f"Command exited with {code}, but expected output {expected_output} is missing.", "ERROR")
                
                # Attempt fallback export if a checkpoint was provided
                if fallback_checkpoint and os.path.exists(fallback_checkpoint):
                    if fallback_export(fallback_checkpoint, expected_output):
                        success = True
            
            if success:
                log(f"Stage '{stage_name}' completed successfully.", "SUCCESS")
                if expected_output:
                    archive_model(expected_output, stage_name.replace(" ", "_"))
                
                state["completed_stages"].append(stage_name)
                save_state(state)
                return True, out
            else:
                log(f"Stage '{stage_name}' failed with code {code}.", "ERROR")
                log(f"Tail of output:\n{out[-1000:]}", "ERROR")
                
        except Exception as e:
            log(f"Exception during '{stage_name}': {e}", "CRITICAL")
            log(traceback.format_exc(), "CRITICAL")
            
        retries += 1
        state["retries"][stage_name] = retries
        save_state(state)
        
        if retries < max_retries:
            wait_time = 60 * retries  # Exponential-ish backoff: 1m, 2m, 3m...
            log(f"Waiting {wait_time}s before retrying...", "WARNING")
            time.sleep(wait_time)
            
    log(f"Stage '{stage_name}' failed after {max_retries} attempts.", "CRITICAL")
    return False, "Failed after max retries"

def generate_report():
    state = load_state()
    report = [
        f"# 🌙 AutoPilot Training Report\n",
        f"**Generated:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n",
        "## Summary\n"
    ]
    
    for stage in state["completed_stages"]:
        report.append(f"- ✅ **{stage}**: Completed\n")
        
    for stage, retries in state["retries"].items():
        if stage not in state["completed_stages"]:
            report.append(f"- ❌ **{stage}**: Failed ({retries} retries)\n")
            
    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        f.write("".join(report))
    log(f"Report generated at {REPORT_FILE}")

def main():
    log("Initializing AutoPilot Enhanced Trainer...")
    
    stages = [
        {
            "name": "Hyper-scale SSGO",
            "cmd": "python3 -m brnext.train.train_robust_ssgo --grid 12 --steps-s1 5000 --steps-s2 5000 --steps-s3 8000 --batch-size 4 --lr 1e-4 --hidden 96 --modes 6 --dropout 0.1 --output ml/experiments/outputs/ssgo_hyper",
            "expected_output": "ml/experiments/outputs/ssgo_hyper/ssgo_robust_medium.onnx",
            "fallback_ckpt": "ml/experiments/outputs/ssgo_hyper/stage3.msgpack"
        },
        {
            "name": "HYBR Meta-Model",
            "cmd": "python3 ml/scripts/a100/train_hybr_meta.py --config ml/configs/hybr_a100.yaml",
            "expected_output": "ml/experiments/outputs/hybr/a100_meta/hybr_ssgo.onnx",
            "fallback_ckpt": "ml/experiments/outputs/hybr/a100_meta/hybr_final.msgpack"
        },
        {
            "name": "Fluid Data Generation",
            "cmd": "python3 -m brml.data.ns_reference_solver --out ml/data/fluid --n-traj 2000",
            "expected_output": None, # Data directory, hard to strictly verify here
            "fallback_ckpt": None
        },
        {
            "name": "Fluid Model Training",
            "cmd": "python3 -m brml.train.train_fnofluid --data-dir ml/data/fluid --steps 50000 --fast",
            "expected_output": "bifrost_fluid.onnx",
            "fallback_ckpt": "fluid_final.msgpack"
        }
    ]

    for stage in stages:
        success, _ = run_with_retry(
            stage["name"], 
            stage["cmd"], 
            expected_output=stage.get("expected_output"),
            fallback_checkpoint=stage.get("fallback_ckpt")
        )
        if not success:
            log(f"Pipeline halted at stage '{stage['name']}' due to failure.", "CRITICAL")
            break
            
    generate_report()
    log("AutoPilot execution finished.")

if __name__ == "__main__":
    main()
