#!/usr/bin/env python3
"""Build, validate and export the DJI Fly command atlas."""

from __future__ import annotations

import argparse
import csv
import json
import sqlite3
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
ATLAS_DIR = REPO_ROOT / "docs" / "dji_fly_atlas"
DEFAULT_DB = ATLAS_DIR / "dji_fly_atlas.sqlite"
DEFAULT_EXPORT_DIR = ATLAS_DIR / "exports"


def connect(db_path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(db_path)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


def initialize(db_path: Path) -> None:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with connect(db_path) as connection:
        connection.executescript((ATLAS_DIR / "schema.sql").read_text(encoding="utf-8"))
        connection.executescript((ATLAS_DIR / "seed.sql").read_text(encoding="utf-8"))
    print(f"initialized {db_path}")


def command_record(connection: sqlite3.Connection, command: sqlite3.Row) -> dict:
    command_id = command["id"]
    record = dict(command)
    record["command_hex"] = f'{command["cmd_set"]:02X}:{command["cmd_id"]:02X}'
    queries = {
        "routes": "SELECT sender, receiver, notes FROM routes WHERE command_id=? ORDER BY sender, receiver",
        "payload_fields": "SELECT message_kind, byte_offset, byte_size, name, data_type, description FROM payload_fields WHERE command_id=? ORDER BY message_kind, byte_offset, name",
        "implementations": "SELECT layer, symbol, source_path, notes FROM implementations WHERE command_id=? ORDER BY layer, symbol",
        "observations": "SELECT observed_at, result, response_hex, evidence_level, notes FROM observations WHERE command_id=? ORDER BY observed_at, id",
    }
    for key, query in queries.items():
        record[key] = [dict(row) for row in connection.execute(query, (command_id,))]
    return record


def export(db_path: Path, output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    with connect(db_path) as connection:
        commands = list(connection.execute("SELECT * FROM commands ORDER BY cmd_set, cmd_id"))
        with (output_dir / "commands.jsonl").open("w", encoding="utf-8") as handle:
            for command in commands:
                payload = command_record(connection, command)
                handle.write(json.dumps(payload, ensure_ascii=False, sort_keys=True) + "\n")

        relationships = list(connection.execute(
            "SELECT source_ref, relation, target_ref, notes FROM relationships ORDER BY source_ref, relation, target_ref"
        ))
        with (output_dir / "relationships.csv").open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow(("source_ref", "relation", "target_ref", "notes"))
            writer.writerows(tuple(row) for row in relationships)

        refs = sorted({row["source_ref"] for row in relationships} | {row["target_ref"] for row in relationships})
        node_ids = {ref: f"n{index}" for index, ref in enumerate(refs)}
        with (output_dir / "atlas.dot").open("w", encoding="utf-8") as handle:
            handle.write("digraph dji_fly_atlas {\n  rankdir=LR;\n")
            for ref in refs:
                handle.write(f"  {node_ids[ref]} [label={json.dumps(ref, ensure_ascii=False)}];\n")
            for row in relationships:
                label = json.dumps(row["relation"], ensure_ascii=False)
                handle.write(f'  {node_ids[row["source_ref"]]} -> {node_ids[row["target_ref"]]} [label={label}];\n')
            handle.write("}\n")
    print(f"exported {len(commands)} commands and {len(relationships)} relationships to {output_dir}")


def check(db_path: Path) -> None:
    with connect(db_path) as connection:
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        foreign_keys = list(connection.execute("PRAGMA foreign_key_check"))
        counts = {
            table: connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            for table in ("commands", "payload_fields", "implementations", "observations", "relationships")
        }
    if integrity != "ok" or foreign_keys:
        raise SystemExit(f"atlas invalid: integrity={integrity!r}, foreign_key_errors={len(foreign_keys)}")
    print("atlas ok " + " ".join(f"{key}={value}" for key, value in counts.items()))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "export", "check", "build"))
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_EXPORT_DIR)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.command in ("init", "build"):
        initialize(args.db)
    if args.command in ("export", "build"):
        export(args.db, args.output_dir)
    if args.command in ("check", "build"):
        check(args.db)


if __name__ == "__main__":
    main()
