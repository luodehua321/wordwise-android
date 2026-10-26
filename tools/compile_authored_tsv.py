#!/usr/bin/env python3
"""Compile explicitly written per-word bilingual examples, never generate prose."""
from pathlib import Path
import argparse,json,re
from apply_internal_review import tokens
ROOT=Path(__file__).resolve().parents[1]
def main():
 parser=argparse.ArgumentParser();parser.add_argument('path',type=Path);args=parser.parse_args();path=args.path
 index={w['word']:w for w in json.loads((ROOT/'app/src/main/assets/vocabulary.json').read_text())};words={};sentences=set()
 for line in path.read_text().splitlines():
  head,en,zh=line.split('\t');assert head in index and head not in words,head
  forms={head.lower()}|{f['word'].lower() for f in index[head]['wordForms']}
  assert forms.intersection(tokens(en)),head
  assert en not in sentences and en[:1].isupper() and en.endswith(('.', '?', '!')),head
  assert re.search('[\u3400-\u9fff]',zh),head
  sentences.add(en);words[head]={'examples':[{'en':en,'zh':zh,'source':'项目原创语境 v17 · CC0','attribution':'项目原创（CC0）'}]}
 scope=path.with_name(path.stem+'_scope.json')
 if scope.exists():assert set(words)=={w['word'] for w in json.loads(scope.read_text())}
 glosses=path.with_name(path.stem+'_glosses.json')
 if glosses.exists():
  for head,patch in json.loads(glosses.read_text()).items():assert head in words;words[head].update(patch)
 path.with_suffix('.json').write_text(json.dumps({'license':'CC0 1.0','words':words},ensure_ascii=False,indent=2)+'\n')
 print(f'Compiled {len(words)} explicit bilingual examples: {path.name}')
if __name__=='__main__':main()
