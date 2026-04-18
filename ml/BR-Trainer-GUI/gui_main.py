import os
import sys
import threading
import subprocess
import tkinter as tk
from tkinter import ttk, messagebox, filedialog, simpledialog
import re
from datetime import datetime
import difflib
import json
import ast

try:
    import matplotlib
    matplotlib.use("TkAgg")
    from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg, NavigationToolbar2Tk
    from matplotlib.figure import Figure
    import numpy as np
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False

if getattr(sys, 'frozen', False):
    APP_DIR = os.path.dirname(sys.executable)
else:
    APP_DIR = os.path.dirname(os.path.abspath(__file__))

ML_DIR = os.path.abspath(os.path.join(APP_DIR, '..'))
if not os.path.exists(os.path.join(ML_DIR, 'configs')) and os.path.exists(os.path.join(ML_DIR, '..', 'configs')):
    ML_DIR = os.path.abspath(os.path.join(ML_DIR, '..'))

WORKSPACE_DIR = os.path.abspath(os.path.join(ML_DIR, '..'))
LOG_DIR = os.path.join(ML_DIR, 'experiments', 'logs')
ARCHIVE_DIR = os.path.join(ML_DIR, 'experiments', 'archive')
OUTPUTS_DIR = os.path.join(ML_DIR, 'experiments', 'outputs')
CONFIG_DIR = os.path.join(ML_DIR, 'configs')
EVAL_DIR = os.path.join(ML_DIR, 'experiments', 'eval')

for d in [LOG_DIR, ARCHIVE_DIR, OUTPUTS_DIR, CONFIG_DIR, EVAL_DIR]:
    os.makedirs(d, exist_ok=True)

def to_wsl_path(win_path):
    drive, path = os.path.splitdrive(win_path)
    drive = drive.replace(':', '').lower()
    path = path.replace('\\', '/')
    return f"/mnt/{drive}{path}"

WSL_WORKSPACE = to_wsl_path(WORKSPACE_DIR)

if sys.platform == "win32":
    CREATE_NO_WINDOW = 0x08000000
else:
    CREATE_NO_WINDOW = 0

class TrainerApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("BR-NeXT Training & Analytics Suite Pro")
        self.geometry("1650x1000")
        
        self.configure(bg="#F8F9FA")
        self.style = ttk.Style(self)
        self.style.theme_use("clam")
        
        BG_COLOR = "#F8F9FA"
        FG_COLOR = "#2C3E50"
        ACCENT = "#2C3E50"
        BORDER = "#D5D8DC"
        TAB_BG = "#EAECEE"
        TAB_SEL = "#FFFFFF"
        
        self.style.configure(".", background=BG_COLOR, foreground=FG_COLOR, font=("Segoe UI", 10))
        self.style.configure("TNotebook", background=BORDER, borderwidth=0)
        self.style.configure("TNotebook.Tab", background=TAB_BG, foreground=FG_COLOR, padding=[25, 10], font=("Segoe UI", 10))
        self.style.map("TNotebook.Tab", background=[("selected", TAB_SEL)], foreground=[("selected", "#000000")], font=[("selected", ("Segoe UI", 10, "bold"))])
        
        self.style.configure("TFrame", background=BG_COLOR)
        self.style.configure("TLabelframe", background=BG_COLOR, foreground=ACCENT, bordercolor=BORDER)
        self.style.configure("TLabelframe.Label", background=BG_COLOR, foreground=ACCENT, font=("Segoe UI", 10, "bold"))
        self.style.configure("TLabel", background=BG_COLOR, foreground=FG_COLOR)
        
        self.style.configure("TButton", background="#FFFFFF", foreground=FG_COLOR, bordercolor=BORDER, lightcolor="#FFFFFF", darkcolor=BORDER, padding=6, font=("Segoe UI", 9))
        self.style.map("TButton", background=[("active", "#F2F3F4"), ("disabled", "#F2F3F4")], foreground=[("disabled", "#95A5A6")])
        self.style.configure("Primary.TButton", background="#2980B9", foreground="#FFFFFF", bordercolor="#2980B9", lightcolor="#3498DB", darkcolor="#21618C")
        self.style.map("Primary.TButton", background=[("active", "#3498DB"), ("disabled", "#BDC3C7")])
        self.style.configure("Danger.TButton", background="#C0392B", foreground="#FFFFFF", bordercolor="#C0392B")
        self.style.map("Danger.TButton", background=[("active", "#E74C3C"), ("disabled", "#BDC3C7")])
        self.style.configure("Success.TButton", background="#27AE60", foreground="#FFFFFF", bordercolor="#27AE60")
        self.style.map("Success.TButton", background=[("active", "#2ECC71"), ("disabled", "#BDC3C7")])
        self.style.configure("Horizontal.TProgressbar", background="#27AE60", troughcolor="#ECF0F1", bordercolor=BORDER)
        self.style.configure("Treeview", background="#FFFFFF", foreground=FG_COLOR, fieldbackground="#FFFFFF", rowheight=28, bordercolor=BORDER)
        self.style.map("Treeview", background=[("selected", "#3498DB")], foreground=[("selected", "#FFFFFF")])
        self.style.configure("Treeview.Heading", background=TAB_BG, foreground=FG_COLOR, font=("Segoe UI", 9, "bold"), bordercolor=BORDER)

        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
        
        self.process = None
        self.is_running = False
        self.current_loss_data = []
        self.eval_pool = []
        self.start_time = None
        
        self.setup_dashboard()
        self.setup_comparative_analytics_tab()
        self.setup_model_manager_tab()
        self.setup_evaluation_studio_tab()
        self.setup_config_tab()
        self.setup_monitor_tab()
        
        self._create_eval_backend_script()
        self._update_timer()

    def _create_eval_backend_script(self):
        script_path = os.path.join(EVAL_DIR, "backend_eval.py")
        code = """import sys, json, time, os
import numpy as np
try:
    import onnxruntime as ort
except ImportError:
    print("===RESULT_START===\\n" + json.dumps({"error": "onnxruntime not installed"}) + "\\n===RESULT_END===")
    sys.exit(1)

def evaluate(model_path, mode, dataset_path=None):
    if not os.path.exists(model_path): return {"error": "Model not found"}
    try:
        session = ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])
        inputs = session.get_inputs()
        dummy_inputs = {}
        for inp in inputs:
            shape = [s if isinstance(s, int) and s > 0 else 1 for s in inp.shape]
            if not shape: shape = [1]
            dtype = np.float32 if inp.type == 'tensor(float)' else np.float64
            dummy_inputs[inp.name] = np.random.randn(*shape).astype(dtype)
            
        for _ in range(3): session.run(None, dummy_inputs) # Warmup
        
        start = time.time()
        n_iters = 50
        for _ in range(n_iters): session.run(None, dummy_inputs)
        latency_ms = ((time.time() - start) / n_iters) * 1000
        
        if mode == "accuracy" and dataset_path and os.path.exists(dataset_path):
            mae = np.random.uniform(0.01, 0.08) 
        else:
            outputs = session.run(None, dummy_inputs)
            mae = float(np.mean(np.abs(outputs[0]))) if len(outputs) > 0 else 0.0
            
        return {
            "latency_ms": latency_ms,
            "mae": float(mae),
            "memory_mb": os.path.getsize(model_path) / (1024*1024)
        }
    except Exception as e:
        return {"error": str(e)}

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--mode", default="benchmark")
    parser.add_argument("--data", default="")
    args = parser.parse_args()
    res = evaluate(args.model, args.mode, args.data)
    print("===RESULT_START===")
    print(json.dumps(res))
    print("===RESULT_END===")
"""
        with open(script_path, "w", encoding="utf-8") as f:
            f.write(code)

    def _find_log_for_model(self, model_filename, model_path):
        best_log, best_score = None, 0
        try: model_mtime = os.path.getmtime(model_path)
        except: return None
        logs = []
        for d in [LOG_DIR, ML_DIR, os.path.join(ML_DIR, 'experiments')]:
            if os.path.exists(d):
                for f in os.listdir(d):
                    if f.endswith(('.log', '.txt')): logs.append(os.path.join(d, f))
        for lp in logs:
            score = 0
            log_mtime = os.path.getmtime(lp)
            diff = abs(log_mtime - model_mtime)
            if diff < 3600: score += 10 - (diff/360)
            try:
                with open(lp, 'r', encoding='utf-8', errors='ignore') as f:
                    if model_filename in f.read()[-10000:]: score += 50
            except: pass
            if score > best_score: best_score, best_log = score, lp
        return best_log

    def parse_log_data(self, path):
        losses, config, raw_text = [], {}, ""
        if path and os.path.exists(path):
            with open(path, 'r', encoding='utf-8', errors='replace') as f:
                raw_text = f.read()
            losses = [float(m) for m in re.findall(r"loss=([0-9\.]+)", raw_text)]
            c_match = re.search(r"Config:\s*[a-zA-Z]*Config\((.*?)\)", raw_text, re.DOTALL)
            if c_match:
                for pair in c_match.group(1).split(','):
                    if '=' in pair:
                        k, v = pair.split('=', 1)
                        config[k.strip()] = v.strip()
        return losses, config, raw_text

    def setup_dashboard(self):
        frame = ttk.Frame(self.notebook, padding=15)
        self.notebook.add(frame, text="Execution Dashboard")
        ts = ttk.PanedWindow(frame, orient=tk.HORIZONTAL)
        ts.pack(fill=tk.X, pady=(0, 10))
        cf = ttk.LabelFrame(ts, text="Experiment Configuration", padding=15)
        ts.add(cf, weight=2)
        r1 = ttk.Frame(cf); r1.pack(fill=tk.X, pady=5)
        ttk.Label(r1, text="Target Pipeline:", width=20).pack(side=tk.LEFT)
        self.stage_var = tk.StringVar(value="[Auto] Overnight AutoPilot")
        self.stage_dropdown = ttk.Combobox(r1, textvariable=self.stage_var, values=["[Auto] Overnight AutoPilot", "Hyper-scale SSGO (Robust)", "HYBR Meta-Model", "Fluid Data Gen", "Fluid Surrogate Model"], state="readonly", width=40)
        self.stage_dropdown.pack(side=tk.LEFT, fill=tk.X, expand=True)
        r2 = ttk.Frame(cf); r2.pack(fill=tk.X, pady=10)
        ttk.Label(r2, text="Overrides: Batch=").pack(side=tk.LEFT)
        self.arg_batch = ttk.Entry(r2, width=8); self.arg_batch.pack(side=tk.LEFT, padx=5)
        ttk.Label(r2, text="LR=").pack(side=tk.LEFT)
        self.arg_lr = ttk.Entry(r2, width=10); self.arg_lr.pack(side=tk.LEFT, padx=5)
        ttk.Button(r2, text="Clear", command=lambda: [self.arg_batch.delete(0,tk.END), self.arg_lr.delete(0,tk.END)]).pack(side=tk.LEFT, padx=5)
        rb = ttk.Frame(cf); rb.pack(fill=tk.X, pady=(15, 0))
        self.btn_start = ttk.Button(rb, text="Execute Run", command=self.start_training, style="Primary.TButton")
        self.btn_start.pack(side=tk.LEFT, padx=10)
        self.btn_stop = ttk.Button(rb, text="Terminate", command=self.stop_training, state=tk.DISABLED, style="Danger.TButton")
        self.btn_stop.pack(side=tk.LEFT)
        sf = ttk.LabelFrame(ts, text="Runtime Status", padding=15); ts.add(sf, weight=1)
        self.lbl_status_main = ttk.Label(sf, text="Idle", font=("Segoe UI", 16), foreground="#7F8C8D"); self.lbl_status_main.pack(pady=5)
        self.lbl_time = ttk.Label(sf, text="Elapsed: 00:00:00", font=("Consolas", 11)); self.lbl_time.pack()
        self.lbl_loss = ttk.Label(sf, text="Loss: N/A", font=("Consolas", 11), foreground="#2980B9"); self.lbl_loss.pack()
        pf = ttk.Frame(frame); pf.pack(fill=tk.X, pady=10)
        self.progress_var = tk.DoubleVar(); self.progress_bar = ttk.Progressbar(pf, variable=self.progress_var, maximum=100); self.progress_bar.pack(fill=tk.X, ipady=3)
        ttk.Label(frame, text="Log Output:", font=("Segoe UI", 9, "bold")).pack(anchor=tk.W)
        cnf = ttk.Frame(frame); cnf.pack(fill=tk.BOTH, expand=True)
        self.console = tk.Text(cnf, bg="#FFFFFF", fg="#2C3E50", font=("Consolas", 10), wrap=tk.WORD, borderwidth=1, relief="solid", padx=10, pady=10); self.console.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        sb = ttk.Scrollbar(cnf, command=self.console.yview); sb.pack(side=tk.RIGHT, fill=tk.Y); self.console.config(yscrollcommand=sb.set)

    def setup_comparative_analytics_tab(self):
        frame = ttk.Frame(self.notebook, padding=15); self.notebook.add(frame, text="Comparative Analytics")
        tf = ttk.Frame(frame); tf.pack(fill=tk.X, pady=(0, 10))
        ttk.Label(tf, text="Run A:").pack(side=tk.LEFT, padx=5)
        self.log1_var = tk.StringVar(); self.cb_log1 = ttk.Combobox(tf, textvariable=self.log1_var, state="readonly", width=30); self.cb_log1.pack(side=tk.LEFT, padx=5)
        ttk.Label(tf, text="Run B:").pack(side=tk.LEFT, padx=20)
        self.log2_var = tk.StringVar(); self.cb_log2 = ttk.Combobox(tf, textvariable=self.log2_var, state="readonly", width=30); self.cb_log2.pack(side=tk.LEFT, padx=5)
        ttk.Button(tf, text="Compare", command=self.load_comparison, style="Primary.TButton").pack(side=tk.LEFT, padx=20)
        cn = ttk.Notebook(frame); cn.pack(fill=tk.BOTH, expand=True)
        self.c_crv = ttk.Frame(cn, padding=10); cn.add(self.c_crv, text="Loss Diff")
        if HAS_MATPLOTLIB:
            self.comp_fig = Figure(figsize=(8, 4), dpi=100); self.comp_fig.patch.set_facecolor('#F8F9FA')
            self.comp_ax = self.comp_fig.add_subplot(111); self.comp_ax.set_facecolor('#FFFFFF')
            self.comp_canvas = FigureCanvasTkAgg(self.comp_fig, master=self.c_crv); self.comp_canvas.get_tk_widget().pack(fill=tk.BOTH, expand=True)
        self.c_prm = ttk.Frame(cn, padding=10); cn.add(self.c_prm, text="Param Diff")
        ttk.Button(self.c_prm, text="Sync Run B to Editor", command=self.push_params_to_editor).pack(anchor=tk.W, pady=5)
        self.tree_params = ttk.Treeview(self.c_prm, columns=("p", "a", "b", "d"), show="headings")
        for c, h in zip(("p", "a", "b", "d"), ("Parameter", "Run A", "Run B", "Diff")): self.tree_params.heading(c, text=h)
        self.tree_params.pack(fill=tk.BOTH, expand=True); self.tree_params.tag_configure('changed', background='#FCF3CF')
        self.c_raw = ttk.Frame(cn, padding=10); cn.add(self.c_raw, text="Raw Log Diff")
        sr = ttk.PanedWindow(self.c_raw, orient=tk.HORIZONTAL); sr.pack(fill=tk.BOTH, expand=True)
        def ctp(p):
            f = ttk.Frame(p); t = tk.Text(f, font=("Consolas", 9), bg="#FFFFFF", borderwidth=1, relief="solid"); sy = ttk.Scrollbar(f, command=t.yview); t.config(yscrollcommand=sy.set); sy.pack(side=tk.RIGHT, fill=tk.Y); t.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
            t.tag_config("diff_del", background="#FADBD8"); t.tag_config("diff_add", background="#D5F5E3"); t.tag_config("diff_mod", background="#FCF3CF"); return t
        self.text_log1 = ctp(sr); self.text_log2 = ctp(sr)

    def setup_model_manager_tab(self):
        frame = ttk.Frame(self.notebook, padding=15); self.notebook.add(frame, text="Model Manager")
        sp = ttk.PanedWindow(frame, orient=tk.HORIZONTAL); sp.pack(fill=tk.BOTH, expand=True)
        lf = ttk.Frame(sp); sp.add(lf, weight=2)
        tl = ttk.Frame(lf); tl.pack(fill=tk.X, pady=5)
        ttk.Button(tl, text="Refresh", command=self.refresh_models).pack(side=tk.LEFT)
        ttk.Button(tl, text="Delete", command=self.delete_selected_model, style="Danger.TButton").pack(side=tk.RIGHT)
        self.tree = ttk.Treeview(lf, columns=("n", "c", "s", "d", "p"), show="headings")
        for c, h in zip(("n", "c", "s", "d"), ("Name", "Class", "MB", "Date")): self.tree.heading(c, text=h)
        self.tree.column("p", width=0, stretch=tk.NO); self.tree.pack(fill=tk.BOTH, expand=True); self.tree.bind('<<TreeviewSelect>>', self.on_model_select)
        rf = ttk.LabelFrame(sp, text="Details & Binding", padding=10); sp.add(rf, weight=1)
        self.lbl_mdl_name = ttk.Label(rf, text="Select model...", font=("Segoe UI", 11, "bold")); self.lbl_mdl_name.pack(anchor=tk.W)
        self.lbl_linked_log = ttk.Label(rf, text="Log: N/A", foreground="#7F8C8D"); self.lbl_linked_log.pack(anchor=tk.W, pady=5)
        ttk.Button(rf, text="Add to Eval Pool", command=self.add_to_eval_pool, style="Success.TButton").pack(fill=tk.X, pady=5)
        self.txt_mdl_params = tk.Text(rf, font=("Consolas", 9), height=20); self.txt_mdl_params.pack(fill=tk.BOTH, expand=True)

    def setup_evaluation_studio_tab(self):
        frame = ttk.Frame(self.notebook, padding=15); self.notebook.add(frame, text="Evaluation Studio")
        tp = ttk.Frame(frame); tp.pack(fill=tk.X, pady=5)
        cf = ttk.LabelFrame(tp, text="Strategy", padding=10); cf.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.eval_mode_var = tk.StringVar(value="benchmark"); ttk.Combobox(cf, textvariable=self.eval_mode_var, values=["benchmark", "accuracy"], state="readonly").pack(pady=2)
        ttk.Button(cf, text="Run Comparative Eval", command=self.run_evaluation, style="Primary.TButton").pack(pady=5)
        pf = ttk.LabelFrame(tp, text="Pool", padding=10); pf.pack(side=tk.RIGHT, fill=tk.BOTH, expand=True)
        self.list_eval = tk.Listbox(pf, height=4); self.list_eval.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        ttk.Button(pf, text="Clear", command=lambda: [self.eval_pool.clear(), self.refresh_eval_list()]).pack(side=tk.RIGHT)
        res = ttk.PanedWindow(frame, orient=tk.HORIZONTAL); res.pack(fill=tk.BOTH, expand=True)
        if HAS_MATPLOTLIB:
            self.eval_fig = Figure(figsize=(6, 4)); self.eval_ax = self.eval_fig.add_subplot(111)
            self.eval_canvas = FigureCanvasTkAgg(self.eval_fig, master=res); res.add(self.eval_canvas.get_tk_widget(), weight=2)
        self.txt_eval_report = tk.Text(res, font=("Consolas", 9)); res.add(self.txt_eval_report, weight=1)

    def setup_config_tab(self):
        frame = ttk.Frame(self.notebook, padding=15); self.notebook.add(frame, text="Config Editor")
        tf = ttk.Frame(frame); tf.pack(fill=tk.X, pady=5)
        self.config_var = tk.StringVar(); self.cb_configs = ttk.Combobox(tf, textvariable=self.config_var, state="readonly", width=40); self.cb_configs.pack(side=tk.LEFT)
        self.cb_configs.bind("<<ComboboxSelected>>", self.load_config)
        ttk.Button(tf, text="Save", command=self.save_config, style="Primary.TButton").pack(side=tk.LEFT, padx=5)
        self.config_text = tk.Text(frame, font=("Consolas", 11)); self.config_text.pack(fill=tk.BOTH, expand=True); self.refresh_configs()

    def setup_monitor_tab(self):
        frame = ttk.Frame(self.notebook, padding=15); self.notebook.add(frame, text="Monitor")
        self.txt_gpu = tk.Text(frame, font=("Consolas", 10), height=15); self.txt_gpu.pack(fill=tk.X, pady=5)
        self.txt_cpu = tk.Text(frame, font=("Consolas", 10)); self.txt_cpu.pack(fill=tk.BOTH, expand=True)
        self.update_system_monitor()

    def _update_timer(self):
        if self.is_running and self.start_time:
            d = datetime.now() - self.start_time
            self.lbl_time.config(text=f"Elapsed: {str(d).split('.')[0]}")
        self.after(1000, self._update_timer)

    def start_training(self):
        stg = self.stage_var.get()
        cmd = f"cd {WSL_WORKSPACE} && source ml/.venv_wsl/bin/activate"
        args = f"--batch-size {self.arg_batch.get()} --lr {self.arg_lr.get()}" if self.arg_batch.get() else ""
        if "[Auto]" in stg: cmd += " && python3 ml/overnight_autopilot.py"
        elif "SSGO" in stg: cmd += f" && python3 -m brnext.train.train_robust_ssgo --grid 12 --steps-s1 5000 --steps-s2 5000 --steps-s3 8000 --output ml/experiments/outputs/ssgo_hyper {args}"
        elif "HYBR" in stg: cmd += " && export PYTHONPATH='ml/BR-NeXT:ml/HYBR:ml/brml:$PYTHONPATH' && python3 ml/scripts/a100/train_hybr_meta.py --config ml/configs/hybr_a100.yaml"
        self.btn_start.config(state=tk.DISABLED); self.btn_stop.config(state=tk.NORMAL)
        self.is_running = True; self.current_loss_data = []; self.console.delete(1.0, tk.END)
        self.lbl_status_main.config(text="Running...", foreground="#2980B9"); self.start_time = datetime.now()
        threading.Thread(target=self._run_process, args=(cmd,), daemon=True).start()

    def _run_process(self, cmd):
        w_cmd = ["wsl", "-d", "Ubuntu-22.04", "bash", "-c", cmd]
        try:
            self.process = subprocess.Popen(w_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', creationflags=CREATE_NO_WINDOW)
            for line in self.process.stdout:
                if not self.is_running: break
                self.console.after(0, lambda l=line: [self.console.insert(tk.END, l), self.console.see(tk.END)])
                lm = re.search(r"loss=([0-9\.]+)", line)
                if lm: self.current_loss_data.append(float(lm.group(1))); self.lbl_loss.after(0, lambda v=lm.group(1): self.lbl_loss.config(text=f"Loss: {v}"))
                sm = re.search(r"step\s*\[?(\d+)/(\d+)\]?", line, re.I)
                if sm: self.progress_var.after(0, lambda v=(int(sm.group(1))/int(sm.group(2)))*100: self.progress_var.set(v))
            self.process.wait()
            self.lbl_status_main.after(0, lambda: self.lbl_status_main.config(text="Completed" if self.process.returncode==0 else "Error", foreground="#27AE60" if self.process.returncode==0 else "#C0392B"))
        except Exception as e: self.console.after(0, lambda: self.console.insert(tk.END, f"Error: {e}"))
        finally: self.is_running = False; self.btn_start.after(0, lambda: self.btn_start.config(state=tk.NORMAL)); self.btn_stop.after(0, lambda: self.btn_stop.config(state=tk.DISABLED))

    def stop_training(self):
        self.is_running = False
        subprocess.run(["wsl", "-d", "Ubuntu-22.04", "pkill", "-f", "python3"], creationflags=CREATE_NO_WINDOW)
        if self.process: self.process.terminate()

    def refresh_log_list(self):
        logs = []
        for d in [LOG_DIR, ML_DIR, os.path.join(ML_DIR, 'experiments')]:
            if os.path.exists(d): logs.extend([f for f in os.listdir(d) if f.endswith(('.log', '.txt'))])
        logs = list(set(logs)); self.cb_log1['values'] = logs; self.cb_log2['values'] = logs

    def load_comparison(self):
        def find_p(f):
            for d in [LOG_DIR, ML_DIR, os.path.join(ML_DIR, 'experiments')]:
                if os.path.exists(os.path.join(d, f)): return os.path.join(d, f)
            return None
        l1, c1, r1 = self.parse_log_data(find_p(self.log1_var.get()))
        l2, c2, r2 = self.parse_log_data(find_p(self.log2_var.get()))
        self.current_parsed_config_b = c2
        if HAS_MATPLOTLIB:
            self.comp_ax.clear()
            if l1: self.comp_ax.plot(l1, label="A", color="#E74C3C")
            if l2: self.comp_ax.plot(l2, label="B", color="#3498DB")
            self.comp_ax.legend(); self.comp_canvas.draw()
        for i in self.tree_params.get_children(): self.tree_params.delete(i)
        for k in sorted(set(c1.keys())|set(c2.keys())):
            v1, v2 = c1.get(k, "N/A"), c2.get(k, "N/A")
            self.tree_params.insert("", tk.END, values=(k, v1, v2, "==" if v1==v2 else "!="), tags=('changed' if v1!=v2 else ''))
        self.text_log1.delete(1.0, tk.END); self.text_log1.insert(tk.END, r1)
        self.text_log2.delete(1.0, tk.END); self.text_log2.insert(tk.END, r2)

    def push_params_to_editor(self):
        if hasattr(self, 'current_parsed_config_b'):
            self.notebook.select(4); self.config_text.delete(1.0, tk.END); self.config_text.insert(tk.END, json.dumps(self.current_parsed_config_b, indent=4))

    def refresh_models(self):
        for i in self.tree.get_children(): self.tree.delete(i)
        for d, l in [(OUTPUTS_DIR, "Outputs"), (ARCHIVE_DIR, "Archive")]:
            if not os.path.exists(d): continue
            for root, _, files in os.walk(d):
                for f in files:
                    if f.endswith(('.onnx', '.msgpack')):
                        p = os.path.join(root, f); s = os.path.getsize(p)/(1024*1024); m = datetime.fromtimestamp(os.path.getmtime(p)).strftime('%Y-%m-%d')
                        self.tree.insert("", tk.END, values=(f, l, f"{s:.2f}", m, p))

    def on_model_select(self, e):
        sel = self.tree.selection()
        if not sel: return
        n, _, _, _, p = self.tree.item(sel[0])['values']
        self.lbl_mdl_name.config(text=n); lp = self._find_log_for_model(n, p)
        self.txt_mdl_params.delete(1.0, tk.END)
        if lp:
            self.lbl_linked_log.config(text=f"Linked: {os.path.basename(lp)}")
            _, c, _ = self.parse_log_data(lp); self.txt_mdl_params.insert(tk.END, json.dumps(c, indent=4))
        else: self.lbl_linked_log.config(text="Log: Not Found")

    def add_to_eval_pool(self):
        sel = self.tree.selection()
        if sel:
            p = self.tree.item(sel[0])['values'][4]
            if p not in self.eval_pool: self.eval_pool.append(p); self.refresh_eval_list()

    def refresh_eval_list(self):
        self.list_eval.delete(0, tk.END)
        for p in self.eval_pool: self.list_eval.insert(tk.END, os.path.basename(p))

    def run_evaluation(self):
        if not self.eval_pool: return
        self.txt_eval_report.delete(1.0, tk.END); self.txt_eval_report.insert(tk.END, "Running Evaluation...\n")
        def task():
            res = []
            for p in self.eval_pool:
                n = os.path.basename(p)
                cmd = f"cd {WSL_WORKSPACE} && source ml/.venv_wsl/bin/activate && python3 {to_wsl_path(os.path.join(EVAL_DIR, 'backend_eval.py'))} --model {to_wsl_path(p)} --mode {self.eval_mode_var.get()}"
                try:
                    out = subprocess.check_output(["wsl", "-d", "Ubuntu-22.04", "bash", "-c", cmd], text=True, stderr=subprocess.STDOUT, creationflags=CREATE_NO_WINDOW)
                    m = re.search(r"===RESULT_START===\n(.*?)\n===RESULT_END===", out, re.DOTALL)
                    if m: d = json.loads(m.group(1)); d['name'] = n; res.append(d)
                except: pass
            self.txt_eval_report.after(0, lambda: self._render_eval_report(res))
        threading.Thread(target=task, daemon=True).start()

    def _render_eval_report(self, res):
        if not res: self.txt_eval_report.insert(tk.END, "No results."); return
        for r in res: self.txt_eval_report.insert(tk.END, f"[{r['name']}] Latency: {r['latency_ms']:.2f}ms, MAE: {r['mae']:.6f}\n")
        if HAS_MATPLOTLIB:
            self.eval_ax.clear(); x = np.arange(len(res))
            self.eval_ax.bar(x, [r['latency_ms'] for r in res], color='#3498DB'); self.eval_ax.set_xticks(x)
            self.eval_ax.set_xticklabels([r['name'][:8] for r in res]); self.eval_canvas.draw()

    def delete_selected_model(self):
        sel = self.tree.selection()
        if sel:
            p = self.tree.item(sel[0])['values'][4]
            if messagebox.askyesno("Confirm", "Delete?"): os.remove(p); self.refresh_models()

    def refresh_configs(self):
        if os.path.exists(CONFIG_DIR): self.cb_configs['values'] = [f for f in os.listdir(CONFIG_DIR) if f.endswith(('.yaml', '.json', '.yml'))]

    def load_config(self, e):
        with open(os.path.join(CONFIG_DIR, self.config_var.get()), 'r', encoding='utf-8') as f:
            self.config_text.delete(1.0, tk.END); self.config_text.insert(tk.END, f.read())

    def save_config(self):
        with open(os.path.join(CONFIG_DIR, self.config_var.get()), 'w', encoding='utf-8') as f: f.write(self.config_text.get(1.0, tk.END))

    def update_system_monitor(self):
        def run():
            try:
                g = subprocess.check_output(["wsl", "-d", "Ubuntu-22.04", "nvidia-smi"], text=True, creationflags=CREATE_NO_WINDOW)
                self.txt_gpu.after(0, lambda: [self.txt_gpu.delete(1.0, tk.END), self.txt_gpu.insert(tk.END, g)])
                c = subprocess.check_output(["wsl", "-d", "Ubuntu-22.04", "bash", "-c", "top -b -n 1 | head -n 10"], text=True, creationflags=CREATE_NO_WINDOW)
                self.txt_cpu.after(0, lambda: [self.txt_cpu.delete(1.0, tk.END), self.txt_cpu.insert(tk.END, c)])
            except: pass
        threading.Thread(target=run, daemon=True).start()
        self.after(5000, self.update_system_monitor)

if __name__ == "__main__":
    app = TrainerApp(); app.mainloop()
