#!/usr/bin/env bash
# 用 uiautomator 找元件座標再點，避免盲點座標。用法：./uitap.sh "Bubble" "Black"
S=${S:?set S=<adb serial>}; P=tw.roastduck.freecues; A="adb -s $S"
dump() { $A shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; $A shell cat /sdcard/u.xml; }
center() { python3 -c "
import sys,re
s=sys.stdin.read(); t=sys.argv[1]
for txt,b in re.findall(r'text=\"([^\"]*)\"[^>]*bounds=\"([^\"]+)\"',s):
    if txt==t:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',b)); print((x1+x2)//2,(y1+y2)//2); break" "$1"; }
$A shell am start -W -n $P/.MainActivity >/dev/null 2>&1; sleep 0.8
if ! dump | grep -q 'text="Sensitivity"'; then
  $A shell input tap 984 175; sleep 0.8
fi
for t in "$@"; do
  xy=$(dump | center "$t"); [ -z "$xy" ] && { echo "not found: $t"; continue; }
  $A shell input tap $xy; echo "tapped $t @ $xy"; sleep 0.6
done
