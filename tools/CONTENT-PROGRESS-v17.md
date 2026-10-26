# Ongoing content completion checkpoint

Android source assets and dist/知词-2.0.10-arm64-v8a-release.apk now match (7,765 added example words; APK SHA256 e05fd3fb00c487aaf7ea53efd0f893e65cdb595746fb34809b057f9661a9c8c4). Reports distinguish the old performance milestone from the latest 72.6 MiB PSS / 3.148 s ready measurement. Do not present this APK as a fully-completed vocabulary release. User explicitly asked full-library completion and accepted long duration. Continue filling all missing examples and translations before final delivery.

iOS project and its temporary build outputs have been deleted as requested; do not recreate iOS. Android performance code is implemented and tested, with a tested R8 release milestone: 63.2 MiB PSS / 2.471 s ready median on API37 emulator, versus 145.4 MiB / 10.145 s debug baseline. Keep the original signing key. Emulator5582 was stopped after measurement.

Replay tools/apply_content_v17.py from the frozen v209 snapshot. New explicit TSV examples compile with tools/compile_authored_tsv.py; it validates exact scope and target tokens, including possessive nouns. The pipeline automatically discovers grade8/9/10 lettered example JSON batches. After any further data changes, regenerate/check prepared assets and rebuild before delivering; current prepared binary/database and APK match current source.

Current example gaps by level: {"1": 0, "2": 0, "3": 0, "4": 0, "5": 0, "6": 0, "7": 0, "8": 0, "9": 626, "10": 4072}
Current untranslated items: 3029

Grade1–8 example gaps are zero. Grade9_a through grade9_f are complete (1,000 explicit bilingual examples in these six batches). Grade9 remaining 626 and grade10 4,072 remain pending, as do 3,029 untranslated example items. The next scope has not yet been frozen. All authored examples are explicitly written contexts, not generic template fills. 551 cumulative meaning/part-of-speech corrections. The prepared DB/binary and tested APK now match current assets.

The public incidental corpus extractor now uses tools/data/v210-before-incidental-corpus.json.gz (frozen from the tested milestone APK) to remain replayable; it never targets current mutable assets. It extracts from the entire licensed Open Dictionary corpus with literal token + Chinese sense overlap; tools/public_bilingual_corpus_v17.json is frozen output, except deliberate new extraction with improved possessive support. Prior full sources, all learner IDs/levels/ranks and nonempty main IPA remain preserved. Current 313+ gloss fixes include historic slur mistakes corrected, modern senses and proper word classes.

Existing docs and performance artifacts need refreshing at the final release; the original word-level source archive and dist removed-examples.json preserve replacements. Remaining IPA must not be fabricated by US-to-UK rewriting. Some relationship fields are genuinely inapplicable; do not force fake synonyms or derivatives.
