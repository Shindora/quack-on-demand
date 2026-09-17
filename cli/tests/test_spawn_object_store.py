"""The per-database object-store secret must be emitted for every tenant-db kind.

TenantDb.validate permits a `memory` (and `duckdb-file`) database to carry a
non-empty objectStore, so the CREATE SECRET the manager authors in
`objectStoreSql` must not be confined to the ducklake branch of the spawn
script. A `memory` database over s3:// parquet is exactly the `qod serve`
remote-prefix case.
"""

import pathlib
import re

REPO = pathlib.Path(__file__).resolve().parents[2]
SH = REPO / "scripts" / "spawn-quack-node.sh"


def _kind_case_start(body: str) -> int:
    # The script has an earlier `case "$kind" in` for arg validation and another
    # for the remote-vs-local mkdir branch; the one that matters here is the
    # last one - the SQL-building dispatch with the ducklake/duckdb-file/memory
    # arms that assemble INIT_SQL.
    idx = body.rfind('case "$kind" in')
    assert idx != -1, "spawn-quack-node.sh no longer has a `case \"$kind\" in` dispatch"
    return idx


def test_object_store_sql_is_emitted_before_the_kind_dispatch():
    body = SH.read_text()
    uses = [m.start() for m in re.finditer(r'\$\{objectStoreSql:-\}', body)]
    assert uses, "spawn-quack-node.sh no longer references objectStoreSql"
    case_start = _kind_case_start(body)
    assert all(pos < case_start for pos in uses), (
        "objectStoreSql is still emitted inside the kind dispatch; a memory or "
        "duckdb-file database carrying objectStore would boot with no CREATE SECRET"
    )


def test_object_store_sql_runs_before_any_attach():
    body = SH.read_text()
    secret_pos = body.index('${objectStoreSql:-}')
    attach_pos = body.index('INIT_SQL+="ATTACH')
    assert secret_pos < attach_pos, "CREATE SECRET must precede the catalog ATTACH"
