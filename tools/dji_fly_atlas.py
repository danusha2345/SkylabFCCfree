#!/usr/bin/env python3
"""Build, validate and export the DJI Fly command atlas."""

from __future__ import annotations

import argparse
import csv
import json
import re
import sqlite3
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
ATLAS_DIR = REPO_ROOT / "docs" / "dji_fly_atlas"
DEFAULT_DB = ATLAS_DIR / "dji_fly_atlas.sqlite"
DEFAULT_EXPORT_DIR = ATLAS_DIR / "exports"
DEFAULT_IMPORT_DIR = ATLAS_DIR / "imports"


def connect(db_path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(db_path)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


CMD_SET_HEADER = re.compile(
    r"^\s*(?P<name>[A-Za-z][A-Za-z0-9_]*)\((?P<value>[^,]+), new UAVCmdSetBase\(\) \{"
)


def parse_cmdset_enums(
    source_path: Path,
    version: str,
    constants: dict[str, int],
    only_cmd_set: str | None = None,
) -> list[dict]:
    lines = source_path.read_text(encoding="utf-8").splitlines()
    rows = []
    headers = [(index, match) for index, line in enumerate(lines) if (match := CMD_SET_HEADER.match(line))]
    for header_index, header in headers:
        cmd_set_name = header.group("name")
        if only_cmd_set is not None and cmd_set_name != only_cmd_set:
            continue
        raw_cmd_set = header.group("value").strip()
        cmd_set = int(raw_cmd_set, 0) if raw_cmd_set.isdecimal() or raw_cmd_set.startswith("0x") else constants.get(raw_cmd_set)
        if cmd_set is None:
            raise ValueError(f"unresolved command set value {raw_cmd_set!r} at line {header_index + 1}")
        enum_start = next(
            index for index in range(header_index + 1, len(lines))
            if "public enum CmdIdType" in lines[index]
        )
        for index in range(enum_start + 1, len(lines)):
            stripped = lines[index].strip()
            if not stripped or stripped.startswith("/*"):
                continue
            if "(" not in stripped or not stripped.endswith((",", ";")):
                if any(row["cmd_set_name"] == cmd_set_name for row in rows):
                    break
                continue
            name, rest = stripped.split("(", 1)
            arguments = rest.rsplit(")", 1)[0]
            parts = [part.strip() for part in arguments.split(",")]
            raw_value = parts[0]
            if raw_value.startswith("0x"):
                cmd_id = int(raw_value, 16)
            elif raw_value.isdecimal():
                cmd_id = int(raw_value)
            else:
                cmd_id = constants.get(raw_value)
            handler = next((part.removesuffix(".class") for part in parts[1:] if part.endswith(".class")), "")
            rows.append({
                "fly_version": version,
                "cmd_set_name": cmd_set_name,
                "cmd_set": cmd_set,
                "cmd_id": "" if cmd_id is None else cmd_id,
                "declared_name": name.strip(),
                "raw_value": raw_value,
                "handler_class": handler,
                "constructor_args": json.dumps(parts[1:], ensure_ascii=False, separators=(",", ":")),
                "source_path": str(source_path),
                "cmd_set_line_number": header_index + 1,
                "line_number": index + 1,
                "is_sentinel": int(name.strip() == "Other" or (cmd_id is not None and cmd_id > 255)),
            })
            if stripped.endswith(";"):
                break
    if not rows:
        label = only_cmd_set or "command set"
        raise ValueError(f"{label} CmdIdType enum not found in {source_path}")
    return rows


def parse_flyc_enum(source_path: Path, version: str, constants: dict[str, int]) -> list[dict]:
    return parse_cmdset_enums(source_path, version, constants, only_cmd_set="FLYC")


def extract_cmdset_enums(
    source_path: Path,
    version: str,
    output_path: Path,
    constants: dict[str, int],
    only_cmd_set: str | None = None,
) -> None:
    rows = parse_cmdset_enums(source_path, version, constants, only_cmd_set=only_cmd_set)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=tuple(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    unresolved = sum(row["cmd_id"] == "" for row in rows)
    scope = only_cmd_set or "all command sets"
    print(f"extracted {len(rows)} declarations from {scope} to {output_path}; unresolved_ids={unresolved}")


def import_declarations(connection: sqlite3.Connection) -> None:
    for csv_path in sorted(DEFAULT_IMPORT_DIR.glob("*.csv")):
        with csv_path.open(encoding="utf-8", newline="") as handle:
            for row in csv.DictReader(handle):
                connection.execute(
                    "INSERT OR IGNORE INTO fly_versions(version, notes) VALUES (?, ?)",
                    (row["fly_version"], "Imported command declarations"),
                )
                if row.get("cmd_set_name"):
                    connection.execute(
                        """
                        INSERT INTO command_sets(fly_version_id, cmd_set, declared_name, source_path, line_number)
                        VALUES ((SELECT id FROM fly_versions WHERE version=?), ?, ?, ?, ?)
                        ON CONFLICT(fly_version_id, cmd_set) DO UPDATE SET
                            declared_name=excluded.declared_name,
                            source_path=excluded.source_path,
                            line_number=excluded.line_number
                        """,
                        (
                            row["fly_version"], int(row["cmd_set"]), row["cmd_set_name"],
                            row["source_path"], int(row.get("cmd_set_line_number") or row["line_number"]),
                        ),
                    )
                connection.execute(
                    """
                    INSERT INTO command_declarations(
                        command_id, fly_version_id, cmd_set, cmd_id, declared_name,
                        raw_value, handler_class, constructor_args, source_path,
                        line_number, is_sentinel
                    )
                    VALUES (
                        (SELECT id FROM commands WHERE cmd_set=? AND cmd_id=?),
                        (SELECT id FROM fly_versions WHERE version=?),
                        ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    ON CONFLICT(fly_version_id, cmd_set, declared_name) DO UPDATE SET
                        command_id=excluded.command_id,
                        cmd_id=excluded.cmd_id,
                        raw_value=excluded.raw_value,
                        handler_class=excluded.handler_class,
                        constructor_args=excluded.constructor_args,
                        source_path=excluded.source_path,
                        line_number=excluded.line_number,
                        is_sentinel=excluded.is_sentinel
                    """,
                    (
                        int(row["cmd_set"]), int(row["cmd_id"]) if row["cmd_id"] else -1,
                        row["fly_version"], int(row["cmd_set"]),
                        int(row["cmd_id"]) if row["cmd_id"] else None,
                        row["declared_name"], row["raw_value"], row["handler_class"],
                        row["constructor_args"], row["source_path"],
                        int(row["line_number"]), int(row["is_sentinel"]),
                    ),
                )


def initialize(db_path: Path) -> None:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with connect(db_path) as connection:
        connection.executescript((ATLAS_DIR / "schema.sql").read_text(encoding="utf-8"))
        connection.executescript((ATLAS_DIR / "seed.sql").read_text(encoding="utf-8"))
        import_declarations(connection)
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

        declarations = list(connection.execute(
            """
            SELECT v.version AS fly_version, d.cmd_set, d.cmd_id, d.declared_name,
                   d.raw_value, d.handler_class, d.constructor_args, d.source_path,
                   d.line_number, d.is_sentinel, d.command_id IS NOT NULL AS curated
            FROM command_declarations d
            JOIN fly_versions v ON v.id=d.fly_version_id
            ORDER BY v.version, d.cmd_set, d.line_number
            """
        ))
        with (output_dir / "declarations.csv").open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow(declarations[0].keys() if declarations else ())
            writer.writerows(tuple(row) for row in declarations)
        coverage = [dict(row) for row in connection.execute(
            "SELECT * FROM declaration_coverage ORDER BY version, cmd_set"
        )]
        (output_dir / "coverage.json").write_text(
            json.dumps(coverage, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        aliases = list(connection.execute(
            """
            SELECT fly_version, cmd_set, cmd_id,
                   group_concat(declared_name, ' | ') AS aliases,
                   COUNT(*) AS declaration_count
            FROM (
                SELECT v.version AS fly_version, d.cmd_set, d.cmd_id, d.declared_name
                FROM command_declarations d
                JOIN fly_versions v ON v.id=d.fly_version_id
                WHERE d.cmd_id IS NOT NULL AND d.is_sentinel=0
                ORDER BY v.version, d.cmd_set, d.cmd_id, d.line_number
            )
            GROUP BY fly_version, cmd_set, cmd_id
            HAVING COUNT(*) > 1
            ORDER BY fly_version, cmd_set, cmd_id
            """
        ))
        with (output_dir / "aliases.csv").open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow(aliases[0].keys() if aliases else ())
            writer.writerows(tuple(row) for row in aliases)

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
    print(
        f"exported {len(commands)} commands, {len(declarations)} declarations, "
        f"{len(aliases)} aliases and {len(relationships)} relationships to {output_dir}"
    )


def check(db_path: Path) -> None:
    with connect(db_path) as connection:
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        foreign_keys = list(connection.execute("PRAGMA foreign_key_check"))
        counts = {
            table: connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            for table in ("commands", "command_declarations", "payload_fields", "implementations", "observations", "relationships")
        }
    if integrity != "ok" or foreign_keys:
        raise SystemExit(f"atlas invalid: integrity={integrity!r}, foreign_key_errors={len(foreign_keys)}")
    print("atlas ok " + " ".join(f"{key}={value}" for key, value in counts.items()))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "export", "check", "build", "extract-flyc-enum", "extract-cmdsets"))
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_EXPORT_DIR)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--version", default="1.21.10")
    parser.add_argument("--import-output", type=Path)
    parser.add_argument("--constant", action="append", default=[], metavar="NAME=VALUE")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.command in ("extract-flyc-enum", "extract-cmdsets"):
        if args.source is None:
            raise SystemExit("--source is required for extract-flyc-enum")
        constants = {}
        for item in args.constant:
            name, value = item.split("=", 1)
            constants[name] = int(value, 0)
        prefix = "flyc_cmd_ids" if args.command == "extract-flyc-enum" else "all_cmd_ids"
        output = args.import_output or DEFAULT_IMPORT_DIR / f"{prefix}_{args.version}.csv"
        only_cmd_set = "FLYC" if args.command == "extract-flyc-enum" else None
        extract_cmdset_enums(args.source, args.version, output, constants, only_cmd_set=only_cmd_set)
        return
    if args.command in ("init", "build"):
        initialize(args.db)
    if args.command in ("export", "build"):
        export(args.db, args.output_dir)
    if args.command in ("check", "build"):
        check(args.db)


if __name__ == "__main__":
    main()
