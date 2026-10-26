#!/usr/bin/env python3
"""Preview/apply the exact v16 Android level review without changing other fields.

Import apply_review(words) into the final Android content pipeline. By default
this CLI is read-only and prints an audit summary. --output writes a reviewed
JSON copy; root's asset pipeline must refresh its metadata and startup caches.
"""
import argparse
import copy
import hashlib
import json
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
REVIEW = HERE / 'level_review_v16.json'
GUARDED_HOMOGRAPHS = ('august', 'August', 'dogged', 'moped', 'timbre', 'timber', 'sere', 'seer', 'tire', 'tier')


def apply_review(words):
    review = json.loads(REVIEW.read_text(encoding='utf-8'))['corrections']
    index = {word['word']: word for word in words}
    assert len(index) == len(words), 'duplicate headwords'
    for spelling, correction in review.items():
        assert spelling in index, f'missing reviewed headword: {spelling}'
        word = index[spelling]
        # Applying the review twice is safe; a third independent review must be
        # reconciled explicitly instead of silently overwriting its level.
        assert word['difficulty'] in {correction['previousDifficulty'], correction['difficulty']}, spelling
        word['difficulty'] = correction['difficulty']
    return words


def audit(words):
    from vocabulary_levels import assign_levels, distribution
    original = copy.deepcopy(words)
    reviewed = apply_review(copy.deepcopy(words))
    reconstructed = copy.deepcopy(original)
    assign_levels(reconstructed)
    assert [word['difficulty'] for word in reviewed] == [word['difficulty'] for word in reconstructed], 'level model does not reproduce review'
    corrections = json.loads(REVIEW.read_text())['corrections']
    changes = []
    old_index = {word['word']: word for word in original}
    for before, after in zip(original, reviewed):
        assert {k:v for k,v in before.items() if k != 'difficulty'} == {k:v for k,v in after.items() if k != 'difficulty'}, before['word']
        if before['difficulty'] != after['difficulty']:
            assert before['word'] in corrections
            changes.append({'word': before['word'], 'before': before['difficulty'], 'after': after['difficulty'],
                'reason': corrections[before['word']]['reason']})
    for spelling in GUARDED_HOMOGRAPHS:
        if spelling in old_index:
            assert next(word for word in reviewed if word['word'] == spelling)['difficulty'] == old_index[spelling]['difficulty'], spelling
    assert apply_review(copy.deepcopy(reviewed)) == reviewed, 'review is not idempotent'
    return reviewed, {
        'wordCount': len(words), 'reviewedWords': len(corrections), 'changedInInput': len(changes),
        'downgraded': sum(row['after'] < row['before'] for row in changes),
        'upgraded': sum(row['after'] > row['before'] for row in changes),
        'beforeDistribution': distribution(original), 'afterDistribution': distribution(reviewed),
        'reviewCategoryCounts': dict(Counter(row['category'] for row in corrections.values())),
        'reviewSHA256': hashlib.sha256(REVIEW.read_bytes()).hexdigest(),
        'homographGuards': list(GUARDED_HOMOGRAPHS),
        'changes': changes,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT/'app/src/main/assets/vocabulary.json')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    words = json.loads(args.source.read_text(encoding='utf-8'))
    reviewed, report = audit(words)
    if args.output:
        args.output.write_text(json.dumps(reviewed, ensure_ascii=False, separators=(',',':'))+'\n', encoding='utf-8')
    if args.report:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({key:value for key,value in report.items() if key != 'changes'}, ensure_ascii=False, indent=2))

if __name__ == '__main__': main()
