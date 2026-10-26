#!/usr/bin/env python3
"""Independent V04 decoder: compare every runtime field with the JSON source."""
import io
import json
import struct
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
stream = io.BytesIO((ASSETS / 'prepared_vocabulary.bin').read_bytes())
def integer():
    return struct.unpack('>i', stream.read(4))[0]
def raw():
    size = integer()
    assert 0 <= size <= 1048576
    return stream.read(size).decode('utf-8')
assert stream.read(8) == b'LINGOV04'
manifest = json.loads((ASSETS / 'dictionary_sources.json').read_text())
assert raw() == manifest['vocabularySha256']
count = integer()
strings = [raw() for _ in range(integer())]
assert len(strings) == len(set(strings))
def reference():
    index = integer()
    assert 0 <= index < len(strings)
    return strings[index]
fields = {'examples': ('en','zh','attribution','sourceUrl'),
          'collocations': ('en','zh'), 'derivatives': ('word','meaningZh'),
          'confusables': ('word','meaningZh','note'), 'wordForms': ('word','meaningZh'), 'synonyms': ('word','meaningZh','senseEn')}
words = sorted(json.loads((ASSETS / 'vocabulary.json').read_text()), key=lambda w:(w['difficulty'],w['rank'],w['id']))
assert len(words) == count
for source in words:
    for name in ('id','word','pronunciationUs','partOfSpeech','meaningZh'):
        assert reference() == source[name], (source['word'],name)
    assert integer() == source['difficulty']
    assert integer() == source['rank']
    for name, keys in fields.items():
        size = integer()
        assert size == len(source.get(name,[]))
        for item in source.get(name,[]):
            for key in keys:
                assert reference() == item.get(key,''), (source['word'],name,key)
    assert reference() == source.get('pronunciationUk','')
assert stream.read() == b''
print(f'Independent V04 decoder verified {count:,} complete records and {len(strings):,} unique strings.')
