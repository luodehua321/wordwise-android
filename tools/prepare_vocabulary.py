#!/usr/bin/env python3
"""Compile the validated source vocabulary for fast, entirely offline app startup.

Run after build_vocabulary.py and after a Room schema change. No network or third-
party packages are used. --check verifies source, schema, hashes and every decoded
binary record without rewriting assets.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import struct
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
MANIFEST = ASSETS / "prepared_vocabulary.json"
DATABASE = ASSETS / "prepared_vocabulary.db"
BINARY = ASSETS / "prepared_vocabulary.bin"
MAGIC = b"LINGOV04"
LIST_FIELDS = {
    "examples": ("en", "zh", "attribution", "sourceUrl"),
    "collocations": ("en", "zh"),
    "derivatives": ("word", "meaningZh"),
    "confusables": ("word", "meaningZh", "note"),
    "wordForms": ("word", "meaningZh"),
    "synonyms": ("word", "meaningZh", "senseEn"),
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source():
    manifest = json.loads((ASSETS / "dictionary_sources.json").read_text())
    source_hash = sha256(ASSETS / "vocabulary.json")
    assert source_hash == manifest["vocabularySha256"], "source vocabulary hash mismatch"
    words = json.loads((ASSETS / "vocabulary.json").read_text())
    assert len(words) == manifest["wordCount"] > 0
    assert len({word["id"] for word in words}) == len(words)
    for word in words:
        assert word["id"] == word["word"].strip() and 1 <= word["difficulty"] <= 10
        assert word["meaningZh"].strip()
    # Matches Room's historical ordering, independent of source JSON order.
    words.sort(key=lambda word: (word["difficulty"], word["rank"], word["id"]))
    schemas = ROOT / "app/schemas/com.yuxuan.lingo.data.LearningDatabase"
    schema_path = max(schemas.glob("*.json"), key=lambda path: int(path.stem))
    schema = json.loads(schema_path.read_text())["database"]
    return manifest, source_hash, words, schema


def write_int(stream, value):
    stream.write(struct.pack(">i", value))


def write_string(stream, value):
    encoded = value.encode("utf-8")
    write_int(stream, len(encoded))
    stream.write(encoded)


def binary_bytes(source_hash, words):
    # Intern once at build time. Runtime reads each distinct UTF-8 string once,
    # then resolves integer references without hashing or duplicate decoding.
    strings = {"": 0}
    def reference(value):
        if value not in strings:
            strings[value] = len(strings)
        return strings[value]
    records = io.BytesIO()
    for word in words:
        for field in ("id", "word", "pronunciationUs", "partOfSpeech", "meaningZh"):
            value = word[field].strip() if field in ("id", "word", "meaningZh") else word[field]
            write_int(records, reference(value))
        write_int(records, word["difficulty"])
        write_int(records, word["rank"])
        for name, fields in LIST_FIELDS.items():
            items = word.get(name, [])
            write_int(records, len(items))
            for item in items:
                for field in fields:
                    write_int(records, reference(item.get(field, "")))
        write_int(records, reference(word.get("pronunciationUk", "")))
    output = io.BytesIO()
    output.write(MAGIC)
    write_string(output, source_hash)
    write_int(output, len(words))
    write_int(output, len(strings))
    for value in strings:
        write_string(output, value)
    output.write(records.getvalue())
    return output.getvalue()


def build_database(path, manifest, source_hash, words, schema):
    with sqlite3.connect(path) as database:
        database.execute("PRAGMA page_size = 4096")
        for entity in schema["entities"]:
            table = entity["tableName"]
            database.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity["indices"]:
                database.execute(index["createSql"].replace("${TABLE_NAME}", table))
        for query in schema["setupQueries"]:
            database.execute(query)
        database.execute(f'PRAGMA user_version = {schema["version"]}')
        database.executemany("INSERT INTO words VALUES (?, ?, ?, ?)", [
            (word["id"], word["difficulty"], word["rank"],
             json.dumps(word, ensure_ascii=False, separators=(",", ":"))) for word in words
        ])
        database.executemany("INSERT INTO app_metadata VALUES (?, ?)", [
            ("vocabularySha256", source_hash),
            ("vocabularySchemaVersion", str(manifest["schemaVersion"])),
        ])
        assert database.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert not database.execute("PRAGMA foreign_key_check").fetchall()
    # Ship a compact, deterministic database, never a WAL or user profile.
    with sqlite3.connect(path) as database:
        database.execute("VACUUM")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    manifest, source_hash, words, schema = source()
    binary = binary_bytes(source_hash, words)
    if args.check:
        prepared = json.loads(MANIFEST.read_text())
        assert prepared["formatVersion"] == 4
        assert prepared["sourceVocabularySha256"] == source_hash
        assert prepared["wordCount"] == len(words)
        assert prepared["roomVersion"] == schema["version"]
        assert prepared["roomIdentityHash"] == schema["identityHash"]
        assert sha256(DATABASE) == prepared["databaseSha256"]
        assert sha256(BINARY) == prepared["binarySha256"]
        assert BINARY.read_bytes() == binary, "binary differs from source records"
        with sqlite3.connect(f"file:{DATABASE}?mode=ro", uri=True) as database:
            assert database.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            rows = database.execute("SELECT payload FROM words ORDER BY difficulty, frequencyRank, id")
            assert [json.loads(row[0]) for row in rows] == words
            for table in ("user_profile", "answer_logs", "review_progress", "calibration_sessions", "calibration_answers"):
                assert database.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0
        print(f"Verified {len(words):,} complete source records, SQLite schema, and both SHA-256 hashes.")
        return
    # Build in a temp directory and replace only complete files.
    with tempfile.TemporaryDirectory() as directory:
        database_path = Path(directory) / DATABASE.name
        build_database(database_path, manifest, source_hash, words, schema)
        DATABASE.write_bytes(database_path.read_bytes())
    BINARY.write_bytes(binary)
    MANIFEST.write_text(json.dumps({
        "formatVersion": 4,
        "sourceVocabularySha256": source_hash,
        "wordCount": len(words),
        "roomVersion": schema["version"],
        "roomIdentityHash": schema["identityHash"],
        "databaseSha256": sha256(DATABASE),
        "binarySha256": sha256(BINARY),
    }, indent=2) + "\n")
    print(f"Prepared {len(words):,} complete words; SQLite {DATABASE.stat().st_size:,} B; binary {BINARY.stat().st_size:,} B.")


if __name__ == "__main__":
    main()
