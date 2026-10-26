#!/usr/bin/env python3
"""Reproduce the flat Wordwise identity from shared polygon geometry (Pillow).
No fonts are needed for the logo. A macOS CJK font is used only for the preview.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
DESIGN = ROOT / 'docs/brand'
TEAL, PAPER, AMBER, INK = '#126B5D', '#FFF9EC', '#F4BB54', '#173E36'
SHAPES = [
 (PAPER, [(25,29),(43,33),(50,39),(50,77),(41,72),(25,68)]),
 (PAPER, [(58,39),(65,33),(83,29),(83,68),(67,72),(58,77)]),
 (TEAL, [(33,43),(43,46),(43,50),(33,47)]),
 (TEAL, [(33,54),(43,57),(43,61),(33,58)]),
 (TEAL, [(65,46),(75,43),(75,47),(65,50)]),
 (TEAL, [(65,57),(75,54),(75,58),(65,61)]),
 (AMBER, [(72,30),(80,28),(80,45),(76,42),(72,47)]),
]
def scaled(points, scale=1):
 return [(54+(x-54)*scale,54+(y-54)*scale) for x,y in points]
def path(points, scale=1):
 return 'M'+' L'.join(f'{x:g},{y:g}' for x,y in scaled(points,scale))+' Z'
def color_for(color,mark=False,mono=False):
 return '#FFFFFF' if mono else (TEAL if color==PAPER else PAPER if color==TEAL else color) if mark else color
def svg(mark=False):
 rows=['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" role="img" aria-label="知词 Wordwise">']
 if not mark: rows.append(f'<rect width="108" height="108" rx="25" fill="{TEAL}"/>')
 rows += [f'<path fill="{color_for(c,mark)}" d="{path(p)}"/>' for c,p in SHAPES]
 return '\n'.join(rows+['</svg>',''])
def vector(scale=.72,mark=False,mono=False):
 rows=['<?xml version="1.0" encoding="utf-8"?>','<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
 rows += [f'    <path android:fillColor="{color_for(c,mark,mono)}" android:pathData="{path(p,scale)}"/>' for c,p in SHAPES if not(mono and c==TEAL)]
 return '\n'.join(rows+['</vector>',''])
def render(size,circular=False,mark=False,mono=False):
 n=size*8; im=Image.new('RGBA',(n,n)); d=ImageDraw.Draw(im)
 if not mark:
  if circular:d.ellipse((0,0,n-1,n-1),fill=TEAL)
  else:d.rounded_rectangle((0,0,n-1,n-1),radius=round(n*25/108),fill=TEAL)
 for c,p in SHAPES:
  if mono and c==TEAL:continue
  d.polygon([(x*n/108,y*n/108) for x,y in p], fill=color_for(c,mark,mono))
 return im.resize((size,size),Image.Resampling.LANCZOS)
def write(relative,text):
 p=RES/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
def main():
 DESIGN.mkdir(parents=True,exist_ok=True)
 write('drawable/ic_launcher_background.xml','<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="'+TEAL+'"/></shape>\n')
 write('drawable/ic_launcher_foreground.xml',vector())
 write('drawable/ic_launcher_monochrome.xml',vector(mono=True))
 write('drawable/ic_wordwise_mark.xml',vector(scale=1,mark=True))
 for folder in ['mipmap-anydpi-v26','mipmap-anydpi-v33']:
  mono='\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>' if folder.endswith('v33') else ''
  for name in ['ic_launcher','ic_launcher_round']:
   write(f'{folder}/{name}.xml','<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background"/>\n    <foreground android:drawable="@drawable/ic_launcher_foreground"/>'+mono+'\n</adaptive-icon>\n')
 for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
  p=RES/f'mipmap-{density}';p.mkdir(parents=True,exist_ok=True)
  render(size).save(p/'ic_launcher.png');render(size,circular=True).save(p/'ic_launcher_round.png')
 (DESIGN/'wordwise-icon.svg').write_text(svg(),encoding='utf-8')
 (DESIGN/'wordwise-mark.svg').write_text(svg(mark=True),encoding='utf-8')
 render(512).save(DESIGN/'wordwise-icon.png')
 render(256,mark=True).save(DESIGN/'wordwise-mark.png')
 store_icon=Image.new('RGB',(512,512),TEAL)
 icon=render(512);store_icon.paste(icon,(0,0),icon);store_icon.save(ROOT/'store/icon-512.png')
 sheet=Image.new('RGB',(1200,680),PAPER);d=ImageDraw.Draw(sheet)
 cjk=Path('/System/Library/Fonts/Hiragino Sans GB.ttc')
 def font(size):return ImageFont.truetype(str(cjk),size) if cjk.exists() else ImageFont.load_default(size=size)
 icon=render(320);sheet.paste(icon,(72,90),icon)
 d.text((452,96),'知词 / Wordwise' if cjk.exists() else 'Wordwise',font=font(54),fill=INK)
 d.text((456,190),'每天多认识一点世界。' if cjk.exists() else 'One word. A wider world.',font=font(24),fill=TEAL)
 d.text((456,247),'翻开的书 · 连续的阅读 · 记住的一词' if cjk.exists() else 'Open pages / continuous reading / a word remembered',font=font(24),fill=INK)
 for i,(c,label) in enumerate([(TEAL,'LEAF  #126B5D'),(PAPER,'PAPER  #FFF9EC'),(AMBER,'BOOKMARK  #F4BB54')]):
  x=456+i*220;d.rectangle((x,332,x+188,384),fill=c,outline=TEAL);d.text((x,402),label,font=font(18),fill=INK)
 d.text((72,486),'FLAT IDENTITY / 2026',font=font(18),fill=TEAL)
 for i,size in enumerate([32,48,72]):
  icon=render(size);sheet.paste(icon,(72+i*120,540),icon)
 icon=render(96,circular=True);sheet.paste(icon,(480,522),icon)
 icon=render(96,mark=True,mono=True);d.rounded_rectangle((660,522,756,618),radius=22,fill=TEAL);sheet.paste(icon,(660,522),icon)
 d.text((820,548),'SVG / Android Vector / PNG',font=font(18),fill=INK)
 sheet.save(DESIGN/'preview.png')
 assert all((x-54)**2+(y-54)**2<33**2 for _,p in SHAPES for x,y in scaled(p,.72))
 print('Generated flat logo, adaptive/themed Android icons, PNGs and preview.')
if __name__=='__main__':main()
