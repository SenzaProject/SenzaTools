#!/usr/bin/env python3
"""Meniru logcat/dmesg/getenforce/setenforce. Perilaku dikendalikan file flag di $FAKE_DIR."""
import os, sys, random
D = os.environ["FAKE_DIR"]; prog = sys.argv[1]; a = sys.argv[2:]
def flag(n): return os.path.exists(f"{D}/flag_{n}")
def grow(f):
    if flag('grow'):
        c = int(open(f"{D}/grow_n").read()) if os.path.exists(f"{D}/grow_n") else 0
        c += 1; open(f"{D}/grow_n", 'w').write(str(c))
        with open(f"{D}/{f}", 'a') as h: h.write(f"11-05 23:59:{c % 60:02d}.000   777   777 I Noise: tick {c}\n" if f == 'logcat.txt' else f"[{99999 + c:12.6f}] noise: tick {c}\n")
if prog == 'logcat':
    if '-c' in a: open(f"{D}/logcat.txt", 'w').close(); sys.exit(0)
    buf = 'crash' if ('-b' in a and a[a.index('-b') + 1] == 'crash') else 'main'
    if '-b' in a and a[a.index('-b') + 1] not in ('crash', 'all', 'main', 'system') : sys.exit(1)
    if flag('no_ball') and '-b' in a and a[a.index('-b') + 1] == 'all':
        sys.stderr.write("Unable to open log device '/dev/socket/logdr': Permission denied\n"); sys.exit(1)
    if buf == 'main': grow('logcat.txt')
    lines = open(f"{D}/{'crash' if buf == 'crash' else 'logcat'}.txt").read().split('\n')[:-1]
    spec = [x for x in a if x.startswith('*:')]
    if spec: lv = 'VDIWEF'.index(spec[0][2]); lines = [l for l in lines if 'VDIWEF'.index(l[31]) >= lv]
    if '-t' in a: lines = lines[-int(a[a.index('-t') + 1]):]
    out = []
    if flag('markers_random'):
        m = ["--------- beginning of main", "--------- beginning of system", "--------- beginning of crash"]
        out = list(lines)
        for x in m: out.insert(random.randint(0, len(out)), x)
    else: out = ["--------- beginning of main"] + lines
    if flag('stderr_noise'): sys.stderr.write("logcat: warning: some harmless note\n")
    sys.stdout.write('\n'.join(out) + '\n')
elif prog == 'dmesg':
    if flag('dmesg_restrict'): sys.stderr.write("dmesg: read kernel buffer failed: Operation not permitted\n"); sys.exit(1)
    grow('dmesg.txt'); sys.stdout.write(open(f"{D}/dmesg.txt").read())
elif prog == 'getenforce':
    sys.stdout.write({'1': 'Enforcing', '0': 'Permissive'}[open(f"{D}/enforce").read().strip()] + '\n')
elif prog == 'setenforce':
    if not flag('force_enforcing'): open(f"{D}/enforce", 'w').write(a[0])
