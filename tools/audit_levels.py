#!/usr/bin/env python3
"""Recompute and expose the evidence for every shipped learning-order label."""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from build_vocabulary import SOURCES, learning_rows
from vocabulary_levels import assign_levels, THRESHOLDS, VERSION, NGSL_SOURCE

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ecdict', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    assert hashlib.sha256(args.ecdict.read_bytes()).hexdigest() == SOURCES['ecdict']['sha256']
    words = json.loads((ROOT / 'app/src/main/assets/vocabulary.json').read_text())
    original = {w['id']: w['difficulty'] for w in words}
    rows = {row['word']: row for row in learning_rows(args.ecdict)}
    report_words = assign_levels(words, rows)
    assert all(w['difficulty'] == original[w['id']] for w in words), 'classification is not reproducible'
    assert len(report_words) == len(words)
    report = {'version': VERSION, 'source': SOURCES['ecdict'], 'auxiliarySource': NGSL_SOURCE,
              'thresholds': THRESHOLDS, 'schoolCaps': {'zk': 4, 'gk': 6},
              'counts': dict(sorted(Counter(w['difficulty'] for w in words).items())),
              'wordCount': len(words), 'allWordsRecomputed': True,
              'limitations': ['项目学习顺序估计；不是CEFR，未做学习者实证标定。',
                              '主题复核针对常用义，不代表同形词全部义项的难度。',
                              'NGSL为固定2801词镜像，不是2023年1.2版本；仅用词头分档。',
                              '其余词仍依赖词频、学段辅助证据；低置信度词需要持续复核。'],
              'words': list(report_words.values())}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('words','source','auxiliarySource')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
