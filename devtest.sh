#!/usr/bin/env bash
# 裝置實測：在桌面上開/關對照找點位，注入加速度後量測點的位移方向。
set -euo pipefail
S=${1:?usage: devtest.sh <adb serial>}
PKG=tw.roastduck.freecues
A="adb -s $S"
cd "$(dirname "$0")"
cmd() { $A shell am start -n $PKG/.MainActivity --ez bg true --es cmd "$@" >/dev/null 2>&1; }
$A install -r FreeCues.apk >/dev/null
$A shell appops set $PKG SYSTEM_ALERT_WINDOW allow
cmd stop; sleep 0.5
$A shell input keyevent HOME; sleep 1.5
$A exec-out screencap -p > /tmp/fc_off.png
cmd start --ez autohide false; sleep 2.5
$A exec-out screencap -p > /tmp/fc_on.png
cmd inject --ef ay 3 --ei n 10; sleep 0.8
$A exec-out screencap -p > /tmp/fc_y.png
cmd inject --ef ax 3 --ei n 10; sleep 0.8
$A exec-out screencap -p > /tmp/fc_x.png
cmd stop --ez autohide true
python3 - <<'EOF'
from PIL import Image
def dots(path):
    """找雙層點：白色內圈連通塊的質心。"""
    b=Image.open(path).convert('RGB'); W,H=b.size; px=b.load()
    off=Image.open('/tmp/fc_off.png').convert('RGB').load()
    mask=lambda x,y: px[x,y][0]>235 and px[x,y][1]>235 and px[x,y][2]>235 and sum(abs(px[x,y][i]-off[x,y][i]) for i in range(3))>60
    seen=set(); out=[]
    for y in range(0,H,2):
        for x in range(0,W,2):
            if (x,y) in seen or not mask(x,y): continue
            st=[(x,y)]; pts=[]
            while st:
                q=st.pop()
                if q in seen: continue
                seen.add(q); qx,qy=q
                if 0<=qx<W and 0<=qy<H and mask(qx,qy):
                    pts.append(q); st+=[(qx+2,qy),(qx-2,qy),(qx,qy+2),(qx,qy-2)]
            if 15<len(pts)<400: out.append((sum(p[0] for p in pts)/len(pts), sum(p[1] for p in pts)/len(pts)))
    return W,out
W,on=dots('/tmp/fc_on.png')
print(f'W={W} dots={len(on)}  x-cols={sorted(set(int(round(x/10)*10) for x,_ in on))}')
print('dots in middle 60%:', sum(1 for x,_ in on if W*0.2<x<W*0.8))
def shift(a,b):
    import statistics
    _,A=dots(a); _,B=dots(b)
    ds=[(min(B,key=lambda q:(q[0]-x)**2+(q[1]-y)**2)) for x,y in A]
    ds=[(c[0]-x,c[1]-y) for (x,y),c in zip(A,ds)]
    return statistics.median(d[0] for d in ds), statistics.median(d[1] for d in ds), len(B)
print('inject ay=+3 (前進加速) → median (dx,dy,n)= (%.1f, %.1f, %d)' % shift('/tmp/fc_on.png','/tmp/fc_y.png'))
print('inject ax=+3 (向右加速) → median (dx,dy,n)= (%.1f, %.1f, %d)' % shift('/tmp/fc_y.png','/tmp/fc_x.png'))
EOF
