#!/usr/bin/env python3
"""Repeatable emulator-only cold launches. Never clears the app's learner data."""
import argparse,json,re,subprocess,time
from pathlib import Path
ADB='/private/tmp/lingo-android-sdk/platform-tools/adb'
PACKAGE='com.yuxuan.lingo'
def duration(value):
 return sum(int(n)*{'h':3600000,'m':60000,'s':1000,'ms':1}[u] for n,u in re.findall(r'(\d+)(ms|h|m|s)',value))
def main():
 p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--phase',required=True);p.add_argument('--runs',type=int,default=5);args=p.parse_args()
 assert args.serial.startswith('emulator-'), 'This diagnostic intentionally supports disposable emulators only.'
 out=Path(__file__).resolve().parents[1]/'dist/validation-v2.0.10/performance';out.mkdir(parents=True,exist_ok=True)
 def adb(*cmd):return subprocess.run([ADB,'-s',args.serial,*cmd],capture_output=True,text=True,check=True).stdout
 rows=[]
 for n in range(args.runs):
  adb('shell','am','force-stop',PACKAGE);adb('logcat','-c')
  launch=adb('shell','am','start','-W','-n',PACKAGE+'/.MainActivity')
  ttid=re.search(r'TotalTime:\s*(\d+)',launch);deadline=time.monotonic()+45;full=None;logs=''
  while time.monotonic()<deadline:
   logs=adb('logcat','-d','-s','ActivityTaskManager:I','LingoStartup:D','LingoLearning:E')
   matches=re.findall(r'Fully drawn '+re.escape(PACKAGE)+r'/\.MainActivity[^:]*:\s*\+([\dhms]+)',logs)
   if matches:full=duration(matches[-1]);break
   time.sleep(.5)
  time.sleep(3)
  mem=adb('shell','dumpsys','meminfo',PACKAGE)
  (out/f'{args.phase}-{n}-memory.txt').write_text(mem);(out/f'{args.phase}-{n}-startup.txt').write_text(launch+'\n'+logs)
  pss=re.search(r'TOTAL PSS:\s*(\d+)',mem);heap=re.search(r'Java Heap:\s*(\d+)',mem)
  row={'iteration':n,'ttidMillis':int(ttid[1])if ttid else None,'ttfdMillis':full,'pssKb':int(pss[1])if pss else None,'javaHeapPssKb':int(heap[1])if heap else None}
  rows.append(row);print(json.dumps(row),flush=True);(out/f'{args.phase}.json').write_text(json.dumps(rows,indent=2)+'\n')
if __name__=='__main__':main()
