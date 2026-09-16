"""Resolve a `qod serve` target string into the tenant-db shape that serves it.

Pure by design: the only I/O is `pathlib` stat plus a directory listing for LOCAL
targets, so every rule here is table-testable without a manager.

Remote prefixes are deliberately never listed. Enumerating an s3:// prefix needs
credentials and a cloud SDK in the CLI, and `pyproject.toml` already carries a
platform-conditional exclusion to keep the dependency set light enough for
`uvx qod` to work everywhere. So a remote prefix resolves to one glob view and
`--table NAME=GLOB` covers multi-table layouts explicitly.
"""

from __future__ import annotations

import glob as _glob
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

# Mirrors ai.starlake.quack.model.Names: 1..63 chars, leading letter or
# underscore, then letters, digits, underscore. Kept in sync by
# test_composed_db_name_mirrors_the_server and require_valid_name's tests.
_NAME_RE = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")
_NAME_MAX = 63

_REMOTE_SCHEMES = (
    "s3://", "s3a://", "r2://", "gs://", "gcs://", "az://", "azure://", "abfss://",
)
_DUCKDB_SUFFIXES = (".duckdb", ".ddb", ".db")
_PARQUET_SUFFIXES = (".parquet", ".pq")
_CSV_SUFFIXES = (".csv", ".tsv", ".csv.gz", ".tsv.gz")
_COMPRESSION_SUFFIXES = (".gz", ".zst", ".bz2")
_GLOB_CHARS = "*?["
_KINDS = ("ducklake", "duckdb-file", "memory")


class TargetError(ValueError):
    """A target the user must fix. Raised before anything is launched."""


@dataclass(frozen=True)
class ServeTarget:
    kind: str  # ducklake | duckdb-file | memory
    name: str  # tenant-db SUFFIX; the server composes <tenant>_<name>
    data_path: str
    metastore: dict = field(default_factory=dict)
    object_store: dict = field(default_factory=dict)
    init_sql: str = ""
    description: str = ""


def sanitize_name(raw: str) -> str:
    """Best-effort slug of a path stem into a Postgres identifier."""
    slug = re.sub(r"[^a-zA-Z0-9_]", "_", (raw or "").strip()).strip("_").lower()
    slug = re.sub(r"_+", "_", slug)
    if slug and slug[0].isdigit():
        slug = "db_" + slug
    return slug[:_NAME_MAX]


def require_valid_name(name: str, origin: str) -> str:
    if not name or len(name) > _NAME_MAX or not _NAME_RE.match(name):
        raise TargetError(
            f"cannot derive a database name from {origin!r} (got {name!r}). Names follow "
            "Postgres identifier rules: 1-63 characters, starting with a letter or "
            "underscore, then letters, digits, or underscores. Pass --name explicitly."
        )
    return name


def composed_db_name(tenant: str, suffix: str) -> str:
    """Mirror of Names.normalizeTenantDbName: the server stores <tenant>_<suffix>
    unless the suffix already carries the prefix. Needed so `qod serve` can match
    an existing row from `database/list` (which returns the COMPOSED name) against
    the suffix `database/create` takes."""
    t, s = tenant.lower(), suffix.lower()
    return s if s.startswith(t + "_") else f"{t}_{s}"


def _sql_str(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _sql_ident(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _stem(filename: str) -> str:
    name = filename
    for suffix in _COMPRESSION_SUFFIXES:
        if name.lower().endswith(suffix):
            name = name[: -len(suffix)]
    return Path(name).stem


def _reader_for(filename: str) -> str | None:
    low = filename.lower()
    if low.endswith(_PARQUET_SUFFIXES):
        return "read_parquet"
    if low.endswith(_CSV_SUFFIXES):
        return "read_csv"
    return None


def _view(name: str, reader: str, target: str, hive: bool) -> str:
    args = _sql_str(target) + (", hive_partitioning = true" if hive else "")
    return f"CREATE OR REPLACE VIEW {_sql_ident(name)} AS SELECT * FROM {reader}({args});"


def _parse_tables(tables: list[str]) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for item in tables:
        table_name, sep, target = item.partition("=")
        if not sep or not table_name.strip() or not target.strip():
            raise TargetError(f"--table expects NAME=GLOB, got {item!r}")
        out.append((table_name.strip(), target.strip()))
    return out


def _table_views(tables: list[str]) -> list[str]:
    return [
        _view(require_valid_name(sanitize_name(n), n), "read_parquet", g, True)
        for n, g in _parse_tables(tables)
    ]


def _views_for_directory(root: Path) -> list[str]:
    """One view per immediate child: a readable FILE becomes a view over that file,
    a SUBDIRECTORY holding parquet at any depth becomes a hive-partitioned view.
    Entries starting with '.' or '_' are skipped, which covers _delta_log,
    _SUCCESS, .DS_Store and friends."""
    stmts: list[str] = []
    for child in sorted(root.iterdir()):
        if child.name.startswith((".", "_")):
            continue
        if child.is_file():
            reader = _reader_for(child.name)
            if reader is not None:
                stmts.append(
                    _view(sanitize_name(_stem(child.name)), reader, str(child.resolve()), False)
                )
        elif child.is_dir() and next(child.rglob("*.parquet"), None) is not None:
            stmts.append(
                _view(
                    sanitize_name(child.name),
                    "read_parquet",
                    f"{child.resolve()}/**/*.parquet",
                    True,
                )
            )
    if not stmts:
        raise TargetError(
            f"no parquet or csv files found under {root}. Point qod serve at a directory "
            "holding .parquet/.csv files, at a single such file, or at a .duckdb file."
        )
    return stmts


def _as_ducklake(display: str, data_path: str, db: str, object_store: dict) -> ServeTarget:
    """`--kind ducklake`: the target is a DuckLake DATA PATH (a local directory or a
    remote prefix) whose catalog tables live in Postgres, not loose parquet to view
    over. The metastore is left empty on purpose: PoolSupervisor fills it from
    quack-on-demand.defaultMetastore (the embedded Postgres, under `qod serve`) and
    force-sets dbName to the composed name."""
    return ServeTarget(
        kind="ducklake",
        name=db,
        data_path=data_path,
        object_store=dict(object_store),
        description=f"DuckLake warehouse at {display}",
    )


def _resolve_remote(
    target: str, name: str | None, tables: list[str], object_store: dict, kind: str | None
) -> ServeTarget:
    prefix = target.rstrip("/")
    last = prefix.rsplit("/", 1)[-1] or "remote"
    db = require_valid_name(name or sanitize_name(last), target)
    if kind == "ducklake":
        return _as_ducklake(prefix + "/", prefix + "/", db, object_store)
    stmts = _table_views(tables) if tables else [
        _view(db, "read_parquet", f"{prefix}/**/*.parquet", True)
    ]
    return ServeTarget(
        kind="memory",
        name=db,
        # NOT a catalog: the spawn script's `memory)` arm never ATTACHes or mkdirs
        # this. It is the SCOPE for the per-database CREATE SECRET, which
        # ObjectStoreSecret.sql derives from dataPath and omits without one.
        data_path=prefix + "/",
        object_store=dict(object_store),
        init_sql="\n".join(stmts),
        description=f"{len(stmts)} view(s) over {prefix}",
    )


def _resolve_glob(target: str, name: str | None) -> ServeTarget:
    pattern = os.path.expanduser(target)
    matches = sorted(m for m in _glob.glob(pattern, recursive=True) if _reader_for(m))
    if not matches:
        raise TargetError(
            f"{target} matched no parquet or csv files. Check the pattern, and quote it so "
            "your shell does not expand it first."
        )
    reader = _reader_for(matches[0])
    db = require_valid_name(name or sanitize_name(_stem(Path(matches[0]).name)), target)
    return ServeTarget(
        kind="memory",
        name=db,
        data_path="",
        init_sql=_view(db, reader, pattern, False),
        description=f"1 view over {target} ({len(matches)} file(s) matched)",
    )


def _resolve_local(
    target: str, name: str | None, schema: str, tables: list[str], kind: str | None,
    object_store: dict,
) -> ServeTarget:
    path = Path(os.path.expanduser(target))
    if not path.exists():
        raise TargetError(f"{path} does not exist")
    if path.is_dir():
        db = require_valid_name(name or sanitize_name(path.resolve().name), target)
        if kind == "ducklake":
            return _as_ducklake(str(path.resolve()), str(path.resolve()), db, object_store)
        stmts = _table_views(tables) if tables else _views_for_directory(path)
        return ServeTarget(
            kind="memory",
            name=db,
            data_path="",
            init_sql="\n".join(stmts),
            description=f"{len(stmts)} view(s) over {path.resolve()}",
        )
    if kind is not None:
        raise TargetError(
            f"--kind {kind} only applies to a directory or a remote prefix; the shape of a "
            f"single file like {path.name} is inferred from its extension."
        )
    if path.name.lower().endswith(_DUCKDB_SUFFIXES):
        db = require_valid_name(name or sanitize_name(path.stem), target)
        return ServeTarget(
            kind="duckdb-file",
            name=db,
            data_path=str(path.resolve()),
            metastore={"dbName": db, "schemaName": schema},
            description=f"DuckDB file {path.resolve()} (single node, read-write)",
        )
    reader = _reader_for(path.name)
    if reader is None:
        raise TargetError(
            f"cannot serve {path}: expected a .duckdb file, a .parquet/.csv file, a "
            "directory of them, or a glob."
        )
    db = require_valid_name(name or sanitize_name(_stem(path.name)), target)
    return ServeTarget(
        kind="memory",
        name=db,
        data_path="",
        init_sql=_view(db, reader, str(path.resolve()), False),
        description=f"1 view over {path.resolve()}",
    )


def resolve(
    target: str | None,
    *,
    kind: str | None = None,
    name: str | None = None,
    schema: str = "main",
    tables: list[str] | None = None,
    object_store: dict | None = None,
    data_root: Path,
) -> ServeTarget:
    """Dispatch order: URI scheme, then glob metacharacters, then a filesystem stat.

    `kind` is an override for the genuinely ambiguous targets only: a directory or
    a remote prefix can hold either loose parquet (views over it, the default) or a
    DuckLake's data files (`kind="ducklake"`). A single file's shape follows from
    its extension, so passing `kind` there is an error rather than a silent no-op.
    """
    tables = list(tables or [])
    store = dict(object_store or {})
    if kind is not None and kind not in _KINDS:
        raise TargetError(f"--kind must be one of {', '.join(_KINDS)}, got {kind!r}")
    if not target:
        db = require_valid_name(name or "main", "--name")
        root = Path(data_root) / "ducklake" / db
        return ServeTarget(
            kind="ducklake",
            name=db,
            data_path=str(root.resolve()),
            description=f"new DuckLake warehouse at {root}",
        )
    if target.lower().startswith(_REMOTE_SCHEMES):
        return _resolve_remote(target, name, tables, store, kind)
    if any(ch in target for ch in _GLOB_CHARS):
        if kind is not None:
            raise TargetError(f"--kind {kind} only applies to a directory or a remote prefix")
        return _resolve_glob(target, name)
    return _resolve_local(target, name, schema, tables, kind, store)
