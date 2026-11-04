#!/usr/bin/env python3
"""Membuat 'perangkat palsu' di folder FAKE_DIR + jawaban benar yang dihitung INDEPENDEN dari kode Kotlin."""
import os, random, sys, json, hashlib, re
d = sys.argv[1]; os.makedirs(d, exist_ok=True)
rnd = random.Random(20261104)
LV = ['V']*5 + ['D']*15 + ['I']*55 + ['W']*15 + ['E']*9 + ['F']*1
TAGS = ['ActivityManager','WindowManager','SurfaceFlinger','Vold','NetworkMonitor','Zygote','InputDispatcher','PowerManagerService','art','chatty']
WORDS = "alpha beta gamma delta kernel binder window layer frame buffer thermal battery modem wifi audio camera sensor display policy cache".split()
def msg(n): return ' '.join(rnd.choice(WORDS) for _ in range(n))
GROUPS = []
for i in range(24):
    GROUPS.append(dict(perms=rnd.choice(['read','write','open','getattr','read open','write read','ioctl','search','read write open']),
                       s=rnd.choice(['untrusted_app','system_server','mediaserver','hal_camera_default','priv_app','platform_app','vold','init']) + (f'_{i}' if i % 3 else ''),
                       t=rnd.choice(['proc_net','sysfs_leds','vendor_file','default_prop','debugfs','block_device','storage_file','tmpfs']) + f'_{i}',
                       c=rnd.choice(['file','dir','chr_file','sock_file']), comms=[f'proc{i}', f'thr{i}x']))
events = []
for n in range(420):
    g = GROUPS[min(int(rnd.expovariate(0.2)), 23)]
    events.append((n + 1000, g, rnd.choice(g['comms']), rnd.choice([0, 0, 0, 1])))
def avc_core(ev, with_id=True):
    sid, g, comm, perm = ev
    perms = ' '.join(rnd.sample(g['perms'].split(), len(g['perms'].split())))   # urutan perms acak: harus dinormalkan
    return (f"type=1400 audit(1699999999.{sid % 1000:03d}:{sid}): " if with_id else "") + \
        f"avc:  denied  {{ {perms} }} for pid={sid} comm=\"{ev[2]}\" name=\"x\" dev=\"tmpfs\" ino=7 scontext=u:r:{g['s']}:s0:c1,c2 tcontext=u:object_r:{g['t']}:s0 tclass={g['c']} permissive={perm}"
# ---------------- logcat (main)
N = 6000
slots = sorted(rnd.sample(range(N), len(events)))
lines = []; ev_iter = iter(events)
ts = 0
for i in range(N):
    ts += rnd.randint(1, 900)
    t = f"11-04 {10 + ts//3600000 % 10:02d}:{ts//60000 % 60:02d}:{ts//1000 % 60:02d}.{ts % 1000:03d}"
    if i in set(slots[:0]) or (slots and i == slots[0]):
        slots.pop(0); ev = next(ev_iter)
        lines.append(f"{t}   501   501 I auditd  : {avc_core(ev)}")
    else:
        lv = rnd.choice(LV)
        lines.append(f"{t} {rnd.randint(300, 9999):5d} {rnd.randint(300, 9999):5d} {lv} {rnd.choice(TAGS)}: {msg(rnd.randint(14, 30))}")
open(f"{d}/logcat.txt", 'w').write('\n'.join(lines) + '\n')
open(f"{d}/crash.txt", 'w').write('\n'.join(
    f"11-04 11:00:0{i}.000  4000  4000 E AndroidRuntime: FATAL EXCEPTION: main {i}" for i in range(7)) + '\n')
# ---------------- dmesg (kejadian avc yang sama, id audit sama, tersebar)
dm = []; k = 0
for i, ev in enumerate(events):
    for _ in range(rnd.randint(5, 14)):
        k += 1; dm.append(f"[{k * 0.0173:12.6f}] {rnd.choice(['binder','mmc0','msm_drm','thermal','usb','cfg80211'])}: {msg(rnd.randint(8, 16))}")
    dm.append(f"[{k * 0.0173:12.6f}] audit: {avc_core(ev)}")
open(f"{d}/dmesg.txt", 'w').write('\n'.join(dm) + '\n')
open(f"{d}/enforce", 'w').write('1')
# ---------------- jawaban benar (independen)
def rank(l):
    m = re.match(r'^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+([VDIWEFA])\s', l); return None if not m else 'VDIWEFA'.index(m.group(1)) if m.group(1) not in 'F' else 5
def rk(l):
    m = re.match(r'^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+([VDIWEFA])\s', l)
    return None if not m else {'V':0,'D':1,'I':2,'W':3,'E':4,'F':5,'A':5}[m.group(1)]
def fp(ls): return hashlib.sha256(''.join(l + '\n' for l in ls).encode()).hexdigest()[:12]
exp = {}
for name, n, lvl in [('LOGCAT_ALL_500', 500, 0), ('LOGCAT_ALL_2000', 2000, 0), ('LOGCAT_WARN_1000', 1000, 3), ('LOGCAT_ERROR_200', 200, 4)]:
    sel = [l for l in lines if (lvl == 0 or (rk(l) or -1) >= lvl)]
    exp[name] = dict(fp=fp(sel[-n:]), n=len(sel[-n:]), first=sel[-n:][0], last=sel[-1])
exp['CRASH_200'] = dict(fp=fp(open(f"{d}/crash.txt").read().split('\n')[:-1][-200:]), n=7)
exp['KERNEL_2000'] = dict(fp=fp(dm[-2000:]), n=2000)
cnt = {}
for sid, g, comm, perm in events:    # id audit unik -> tiap kejadian dihitung sekali
    key = ' '.join(sorted(g['perms'].split())) + f"|u:r:{g['s']}:s0:c1,c2|u:object_r:{g['t']}:s0|{g['c']}"
    cnt[key] = cnt.get(key, 0) + 1
exp['AVC'] = sorted(([k, v] for k, v in cnt.items()), key=lambda x: (-x[1], x[0]))
exp['AVC_total'] = len(events)
# ada berapa kejadian di dalam jendela lama? (dmesg tail 500 / logcat -t 2000)
exp['old_dmesg_window_events'] = sum(1 for l in dm[-500:] if 'avc:' in l)
exp['old_logcat_window_events'] = sum(1 for l in lines[-2000:] if 'avc:' in l)
json.dump(exp, open(f"{d}/expected.json", 'w'), indent=1)
print("fixture ok:", len(lines), "baris logcat,", len(dm), "baris dmesg,", len(events), "kejadian avc,", len(cnt), "kelompok,",
      "logcat chars last2000:", sum(len(l) + 1 for l in lines[-2000:]))
