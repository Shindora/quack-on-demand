import pytest

from qod_cli.serve_target import (
    ServeTarget,
    TargetError,
    composed_db_name,
    require_valid_name,
    resolve,
    sanitize_name,
)


def test_bare_target_is_a_fresh_ducklake(tmp_path):
    t = resolve(None, data_root=tmp_path)
    assert t.kind == "ducklake"
    assert t.name == "main"
    assert t.data_path == str((tmp_path / "ducklake" / "main").resolve())
    assert t.metastore == {}
    assert t.init_sql == ""


def test_duckdb_file_target(tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    t = resolve(str(f), data_root=tmp_path)
    assert t.kind == "duckdb-file"
    assert t.name == "sales"
    assert t.data_path == str(f.resolve())
    assert t.metastore == {"dbName": "sales", "schemaName": "main"}


def test_duckdb_file_honors_schema_override(tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    t = resolve(str(f), schema="analytics", data_root=tmp_path)
    assert t.metastore["schemaName"] == "analytics"


def test_local_directory_becomes_views(tmp_path):
    data = tmp_path / "data"
    (data / "orders").mkdir(parents=True)
    (data / "orders" / "part-0.parquet").write_bytes(b"")
    (data / "customers.parquet").write_bytes(b"")
    (data / "regions.csv").write_text("a,b\n")
    (data / "_ignored.parquet").write_bytes(b"")
    t = resolve(str(data), data_root=tmp_path)
    assert t.kind == "memory"
    assert t.name == "data"
    assert t.data_path == ""
    assert 'CREATE OR REPLACE VIEW "customers" AS SELECT * FROM read_parquet(' in t.init_sql
    assert 'CREATE OR REPLACE VIEW "regions" AS SELECT * FROM read_csv(' in t.init_sql
    assert 'CREATE OR REPLACE VIEW "orders" AS SELECT * FROM read_parquet(' in t.init_sql
    assert "hive_partitioning = true" in t.init_sql
    assert "_ignored" not in t.init_sql


def test_empty_directory_is_an_error(tmp_path):
    empty = tmp_path / "empty"
    empty.mkdir()
    with pytest.raises(TargetError, match="no parquet or csv files"):
        resolve(str(empty), data_root=tmp_path)


def test_single_parquet_file(tmp_path):
    f = tmp_path / "events.parquet"
    f.write_bytes(b"")
    t = resolve(str(f), data_root=tmp_path)
    assert t.kind == "memory"
    assert t.name == "events"
    assert 'VIEW "events"' in t.init_sql


def test_missing_target_is_an_error(tmp_path):
    with pytest.raises(TargetError, match="does not exist"):
        resolve(str(tmp_path / "nope.duckdb"), data_root=tmp_path)


def test_unsupported_file_type_is_an_error(tmp_path):
    f = tmp_path / "notes.txt"
    f.write_text("hi")
    with pytest.raises(TargetError, match="cannot serve"):
        resolve(str(f), data_root=tmp_path)


def test_remote_prefix_gets_one_glob_view(tmp_path):
    t = resolve("s3://bucket/sales/", object_store={"s3_region": "eu-west-1"}, data_root=tmp_path)
    assert t.kind == "memory"
    assert t.name == "sales"
    # The prefix rides dataPath purely as the object-store SCOPE.
    assert t.data_path == "s3://bucket/sales/"
    assert t.object_store == {"s3_region": "eu-west-1"}
    assert "s3://bucket/sales/**/*.parquet" in t.init_sql
    assert t.init_sql.count("CREATE OR REPLACE VIEW") == 1


def test_remote_prefix_with_explicit_tables(tmp_path):
    t = resolve(
        "s3://bucket/wh/",
        tables=["orders=s3://bucket/wh/orders/**/*.parquet", "items=s3://bucket/wh/items/*.parquet"],
        data_root=tmp_path,
    )
    assert t.init_sql.count("CREATE OR REPLACE VIEW") == 2
    assert 'VIEW "orders"' in t.init_sql
    assert 'VIEW "items"' in t.init_sql


def test_malformed_table_spec_is_an_error(tmp_path):
    with pytest.raises(TargetError, match="NAME=GLOB"):
        resolve("s3://bucket/wh/", tables=["orders"], data_root=tmp_path)


def test_glob_target(tmp_path):
    (tmp_path / "a.parquet").write_bytes(b"")
    (tmp_path / "b.parquet").write_bytes(b"")
    t = resolve(str(tmp_path / "*.parquet"), name="stuff", data_root=tmp_path)
    assert t.kind == "memory"
    assert t.name == "stuff"
    assert t.init_sql.count("CREATE OR REPLACE VIEW") == 1


def test_glob_matching_nothing_is_an_error(tmp_path):
    with pytest.raises(TargetError, match="matched no"):
        resolve(str(tmp_path / "*.parquet"), data_root=tmp_path)


def test_name_override_wins(tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    t = resolve(str(f), name="warehouse", data_root=tmp_path)
    assert t.name == "warehouse"
    assert t.metastore["dbName"] == "warehouse"


def test_undeivable_name_is_an_error(tmp_path):
    f = tmp_path / "---.duckdb"
    f.write_bytes(b"")
    with pytest.raises(TargetError, match="--name"):
        resolve(str(f), data_root=tmp_path)


def test_sql_string_literals_are_escaped(tmp_path):
    odd = tmp_path / "it's data"
    odd.mkdir()
    (odd / "t.parquet").write_bytes(b"")
    t = resolve(str(odd), name="odd", data_root=tmp_path)
    assert "it''s data" in t.init_sql


def test_kind_ducklake_on_a_remote_prefix(tmp_path):
    # An s3 prefix that holds a DuckLake's data files, not loose parquet to view.
    t = resolve("s3://bucket/lake/", kind="ducklake", object_store={"s3_region": "eu-west-1"},
                data_root=tmp_path)
    assert t.kind == "ducklake"
    assert t.name == "lake"
    assert t.data_path == "s3://bucket/lake/"
    assert t.metastore == {}
    assert t.init_sql == ""
    assert t.object_store == {"s3_region": "eu-west-1"}


def test_kind_ducklake_on_a_local_directory(tmp_path):
    lake = tmp_path / "lakedata"
    lake.mkdir()
    t = resolve(str(lake), kind="ducklake", data_root=tmp_path)
    assert t.kind == "ducklake"
    assert t.name == "lakedata"
    assert t.data_path == str(lake.resolve())
    assert t.init_sql == ""


def test_kind_is_refused_on_a_single_file(tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    with pytest.raises(TargetError, match="only applies"):
        resolve(str(f), kind="ducklake", data_root=tmp_path)


def test_unknown_kind_is_an_error(tmp_path):
    with pytest.raises(TargetError, match="ducklake"):
        resolve("s3://bucket/x/", kind="sqlite", data_root=tmp_path)


@pytest.mark.parametrize(
    "raw,expected",
    [("sales", "sales"), ("My-Data", "my_data"), ("2024", "db_2024"), ("a.b.c", "a_b_c")],
)
def test_sanitize_name(raw, expected):
    assert sanitize_name(raw) == expected


def test_require_valid_name_rejects_bad_identifiers():
    with pytest.raises(TargetError, match="--name"):
        require_valid_name("", "x")
    with pytest.raises(TargetError, match="--name"):
        require_valid_name("9lives", "x")


def test_composed_db_name_mirrors_the_server():
    # Names.normalizeTenantDbName: prefix unless the suffix already carries it.
    assert composed_db_name("acme", "sales") == "acme_sales"
    assert composed_db_name("acme", "acme_sales") == "acme_sales"
    assert composed_db_name("ACME", "Sales") == "acme_sales"
