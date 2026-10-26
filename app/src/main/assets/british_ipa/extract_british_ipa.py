#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Project-written source selection code, distributed with the British IPA data.
"""Reproduce or check pronunciationUk from the complete adjacent en_UK.txt.

Usage: python3 extract_british_ipa.py vocabulary.json [output.json]
Without output.json this verifies the existing UK fields without writing files.
The original RP source takes precedence over supplement.json; remaining gaps stay empty.
"""
import hashlib
import json
from pathlib import Path
import sys


def main():
    if len(sys.argv) not in (2, 3):
        raise SystemExit(__doc__)
    raw = Path(__file__).with_name('en_UK.txt').read_bytes()
    assert hashlib.sha256(raw).hexdigest() == '221394caef0cf723b4f2df81a98ac33191293257b88aed5b1fb89466d3a0dc77'
    pronunciations = {word: row['ipa'] for word, row in json.loads(Path(__file__).with_name('supplement.json').read_text(encoding='utf-8')).items()}
    pronunciations.update(dict(line.split('\t', 1) for line in raw.decode('utf-8').splitlines()))
    extra = Path(__file__).with_name('oewn_detail_completions.json')
    if extra.exists():
        for row in json.loads(extra.read_text()):
            if row['field'] == 'pronunciationUk': pronunciations.setdefault(row['word'],row['value'])
    words = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    if len(sys.argv) == 2:
        assert all(word['pronunciationUk'] == pronunciations.get(word['word'], pronunciations.get(word['word'].lower(), '')) for word in words)
    else:
        for word in words:
            word['pronunciationUk'] = pronunciations.get(word['word'], pronunciations.get(word['word'].lower(), ''))
        Path(sys.argv[2]).write_text(json.dumps(words, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    print(f'Verified UK IPA source; {sum(bool(word["pronunciationUk"]) for word in words)}/{len(words)} matched or reviewed entries.')


if __name__ == '__main__':
    main()
