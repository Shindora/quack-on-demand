import pathlib

import httpx
import pytest

from qod_cli import launcher
from qod_cli.config import load_settings, load_start_env

BASE = "http://localhost:20900"


@pytest.fixture
def wired(monkeypatch, tmp_path):
    """Stub provisioning and capture the exec, so no JVM or Postgres is launched."""
    from qod_cli.commands import serve as serve_cmd

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    captured = {}
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(
        launcher, "ensure_duckdb_cli", lambda cache_dir=None, **kw: tmp_path / "duckdb" / "bin"
    )
    monkeypatch.setattr(
        launcher, "ensure_libduckdb", lambda cache_dir=None, **kw: tmp_path / "duckdb" / "lib"
    )
    monkeypatch.setattr(
        launcher, "materialize_spawn_scripts", lambda dest: (tmp_path / "s.sh", tmp_path / "s.ps1")
    )
    monkeypatch.setattr(launcher, "default_data_dir", lambda: tmp_path / "state")
    monkeypatch.setattr(launcher, "default_cache_dir", lambda: tmp_path / "cache")

    def fake_exec(cmd, env=None):
        captured["cmd"], captured["env"] = cmd, env

    monkeypatch.setattr(serve_cmd, "_exec", fake_exec)
    # The provisioning thread would otherwise poll a manager that never boots.
    monkeypatch.setattr(serve_cmd, "_spawn_provisioning", lambda **kw: captured.setdefault("provision", kw))
    monkeypatch.setattr(serve_cmd.os, "chdir", lambda d: captured.setdefault("cwd", d))
    captured["jar"] = jar
    captured["tmp"] = tmp_path
    return captured


def _invoke(runner, wired, *args):
    from qod_cli.main import app

    return runner.invoke(app, ["serve", *args, "--jar", str(wired["jar"])])


def test_serve_sets_the_embedded_control_plane_env(runner, wired, tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    env = wired["env"]
    assert env["QOD_PG_EMBEDDED"] == "true"
    assert env["QOD_PG_EMBEDDED_PORT"] == "25432"
    assert env["QOD_PG_EMBEDDED_DATA_DIR"] == str(tmp_path / "state" / "pg")


def test_serve_turns_acl_on_explicitly(runner, wired, tmp_path):
    # quack-on-demand.acl.enabled defaults to FALSE in application.conf, so a
    # persistent install has to ask for it.
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    assert wired["env"]["QOD_ACL_ENABLED"] == "true"


def test_serve_generates_and_persists_an_admin_password(runner, wired, tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    generated = wired["env"]["QOD_ADMIN_PASSWORD"]
    assert len(generated) >= 12
    assert load_start_env()["QOD_ADMIN_PASSWORD"] == generated


def test_serve_reuses_a_stored_admin_password(runner, wired, tmp_path):
    from qod_cli.config import save_start_env

    save_start_env({"QOD_ADMIN_PASSWORD": "already-set"})
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    assert wired["env"]["QOD_ADMIN_PASSWORD"] == "already-set"


def test_serve_lets_a_real_env_var_win(runner, wired, tmp_path, monkeypatch):
    monkeypatch.setenv("QOD_ADMIN_PASSWORD", "from-env")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    assert wired["env"]["QOD_ADMIN_PASSWORD"] == "from-env"


def test_serve_passes_the_resolved_target_to_provisioning(runner, wired, tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    target = wired["provision"]["target"]
    assert target.kind == "duckdb-file"
    assert target.name == "sales"
    assert target.metastore == {"dbName": "sales", "schemaName": "main"}
    assert wired["provision"]["tenant"] == "default"
    assert wired["provision"]["pool"] == "bi"


def test_serve_honors_the_port_and_data_dir_flags(runner, wired, tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f), "--pg-port", "26000", "--pg-data-dir", "/custom/pg")
    assert wired["env"]["QOD_PG_EMBEDDED_PORT"] == "26000"
    assert wired["env"]["QOD_PG_EMBEDDED_DATA_DIR"] == "/custom/pg"


def test_serve_honors_pg_port_env_var_over_default(runner, wired, tmp_path, monkeypatch):
    # M-3: explicit flag > env var > persisted qod-setup value > default. With no
    # flag, a real QOD_PG_EMBEDDED_PORT must not be clobbered by the flag's default.
    monkeypatch.setenv("QOD_PG_EMBEDDED_PORT", "26500")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    assert wired["env"]["QOD_PG_EMBEDDED_PORT"] == "26500"


def test_serve_warns_loudly_when_acl_is_overridden_off(runner, wired, tmp_path):
    # I-1: a QOD_ACL_ENABLED=false persisted by `qod setup` outranks serve's
    # setdefault silently today; it must at least warn.
    from qod_cli.config import save_start_env

    save_start_env({"QOD_ACL_ENABLED": "false"})
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert wired["env"]["QOD_ACL_ENABLED"] == "false"
    assert "WARN" in result.output
    assert "QOD_ACL_ENABLED=false" in result.output


def test_serve_rejects_size_greater_than_1_for_a_duckdb_file(runner, wired, tmp_path):
    # M-1: DuckDB's single-writer file lock means only one node can attach a
    # .duckdb file read-write.
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f), "--size", "2")
    assert result.exit_code == 1
    assert "single-writer" in result.output
    assert "cmd" not in wired


def test_serve_fails_before_launching_on_a_missing_target(runner, wired, tmp_path):
    result = _invoke(runner, wired, str(tmp_path / "nope.duckdb"))
    assert result.exit_code == 1
    assert "does not exist" in result.output
    assert "cmd" not in wired


def test_serve_fails_before_launching_when_the_name_equals_the_tenant(runner, wired, tmp_path):
    # Names.normalizeTenantDbName refuses suffix == tenant (DuckDB cannot attach a
    # catalog under an existing catalog's name). Catch it before booting a JVM.
    f = tmp_path / "default.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 1
    assert "--name" in result.output
    assert "cmd" not in wired


def test_serve_collects_s3_credentials_from_flags(runner, wired):
    _invoke(
        runner, wired, "s3://bucket/sales/",
        "--access-key-id", "AK", "--secret-access-key", "SK", "--region", "eu-west-1",
    )
    target = wired["provision"]["target"]
    assert target.object_store == {
        "s3_access_key_id": "AK",
        "s3_secret_access_key": "SK",
        "s3_region": "eu-west-1",
    }
    assert target.data_path == "s3://bucket/sales/"


def test_serve_falls_back_to_ambient_aws_env(runner, wired, monkeypatch):
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "envAK")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "envSK")
    _invoke(runner, wired, "s3://bucket/sales/")
    assert wired["provision"]["target"].object_store["s3_access_key_id"] == "envAK"


def test_serve_kind_override_reaches_resolution(runner, wired):
    _invoke(runner, wired, "s3://bucket/lake/", "--kind", "ducklake")
    target = wired["provision"]["target"]
    assert target.kind == "ducklake"
    assert target.data_path == "s3://bucket/lake/"
    assert target.init_sql == ""


def test_serve_rejects_an_unknown_kind(runner, wired):
    result = _invoke(runner, wired, "s3://bucket/lake/", "--kind", "sqlite")
    assert result.exit_code == 1
    assert "ducklake" in result.output
    assert "cmd" not in wired


def test_bare_serve_provisions_a_fresh_ducklake(runner, wired, tmp_path):
    _invoke(runner, wired)
    target = wired["provision"]["target"]
    assert target.kind == "ducklake"
    assert target.name == "main"


def test_provisioning_logs_in_and_ensures_everything(respx_mock, tmp_path):
    from qod_cli.commands.serve import _provision
    from qod_cli.serve_target import resolve

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    respx_mock.post(f"{BASE}/api/auth/login").mock(
        return_value=httpx.Response(200, json={"token": "jwt-1", "username": "admin"})
    )
    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": []})
    )
    respx_mock.post(f"{BASE}/api/tenant/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": []})
    )
    respx_mock.post(f"{BASE}/api/database/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        return_value=httpx.Response(200, json={"pools": []})
    )
    respx_mock.post(f"{BASE}/api/pool/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/config/client").mock(
        return_value=httpx.Response(
            200, json={"flightSqlHost": "0.0.0.0", "flightSqlPort": 31338, "flightSqlTls": True}
        )
    )

    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    target = resolve(str(f), data_root=tmp_path)
    lines = []
    _provision(
        manager_url=BASE, tenant="default", target=target, pool="bi", size=1,
        password="pw", profile="default", generated=True, ready_timeout=5,
        pg_port=25432, pg_data_dir=str(tmp_path / "pg"), echo=lines.append,
    )
    banner = "\n".join(lines)
    assert "jdbc:arrow-flight-sql://localhost:31338/" in banner
    assert "tenant=default" in banner and "pool=bi" in banner
    assert "pw" in banner  # generated passwords are shown once
    assert load_settings().token == "jwt-1"


def test_provisioning_reports_and_keeps_the_manager_on_failure(respx_mock, tmp_path):
    from qod_cli.commands.serve import _provision
    from qod_cli.serve_target import resolve

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    respx_mock.post(f"{BASE}/api/auth/login").mock(
        return_value=httpx.Response(200, json={"token": "jwt-1"})
    )
    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(500, json={"error": "boom", "message": "database is down"})
    )
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    lines = []
    _provision(
        manager_url=BASE, tenant="default", target=resolve(str(f), data_root=tmp_path),
        pool="bi", size=1, password="pw", profile="default", generated=True,
        ready_timeout=5, pg_port=25432, pg_data_dir=str(tmp_path / "pg"), echo=lines.append,
    )
    out = "\n".join(lines)
    assert "list tenants" in out
    assert "still running" in out
    assert "qod tenant list" in out


def test_stored_password_is_not_reprinted(respx_mock, tmp_path):
    from qod_cli.commands.serve import _banner

    banner = _banner(
        tenant="default", db="sales", pool="bi", size=1, password="secret", generated=False,
        edge_host="localhost", edge_port=31338, manager_url=BASE,
        pg_port=25432, pg_data_dir="/x/pg", description="DuckDB file /abs/sales.duckdb",
    )
    assert "secret" not in banner
    assert "qod user update" in banner
    # M-2: the "stored in <config_path>" line must survive even when this run did
    # not generate the password, so a JVM death before the generating run's banner
    # doesn't strand the user with zero mention of where the password lives.
    from qod_cli.config import config_path

    assert f"password stored in {config_path()}" in banner


def test_generated_password_banner_still_shows_the_plaintext_once(respx_mock, tmp_path):
    from qod_cli.commands.serve import _banner
    from qod_cli.config import config_path

    banner = _banner(
        tenant="default", db="sales", pool="bi", size=1, password="secret", generated=True,
        edge_host="localhost", edge_port=31338, manager_url=BASE,
        pg_port=25432, pg_data_dir="/x/pg", description="DuckDB file /abs/sales.duckdb",
    )
    assert "password      : secret" in banner
    assert f"password stored in {config_path()}" in banner


def test_banner_gives_a_two_step_hint_for_adding_a_user_under_acl(respx_mock, tmp_path):
    # M-5: ACL is forced on, so a bare `qod user create` principal is denied on
    # every table (only superusers bypass) - the banner must not send users into
    # that dead end without pointing at role/membership.
    from qod_cli.commands.serve import _banner

    banner = _banner(
        tenant="default", db="sales", pool="bi", size=1, password="secret", generated=False,
        edge_host="localhost", edge_port=31338, manager_url=BASE,
        pg_port=25432, pg_data_dir="/x/pg", description="DuckDB file /abs/sales.duckdb",
    )
    assert "qod user create" in banner
    assert "grant access" in banner
    assert "qod role" in banner
    assert "qod membership add" in banner


def test_provisioning_never_raises_on_a_tokenless_login(respx_mock, tmp_path):
    # I-2: an uncaught KeyError on login["token"] would surface as a raw
    # traceback on the daemon thread, interleaved with the manager log.
    from qod_cli.commands.serve import _provision
    from qod_cli.serve_target import resolve

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    respx_mock.post(f"{BASE}/api/auth/login").mock(return_value=httpx.Response(200, json={}))

    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    lines = []
    _provision(
        manager_url=BASE, tenant="default", target=resolve(str(f), data_root=tmp_path),
        pool="bi", size=1, password="pw", profile="default", generated=True,
        ready_timeout=5, pg_port=25432, pg_data_dir=str(tmp_path / "pg"), echo=lines.append,
    )
    out = "\n".join(lines)
    assert "provisioning failed unexpectedly" in out
    assert "still running" in out


def test_provisioning_substitutes_a_null_flight_sql_host(respx_mock, tmp_path):
    # I-2: a JSON-null flightSqlHost must take the same substitution branch as
    # "" and "0.0.0.0", not land None in the JDBC connection string.
    from qod_cli.commands.serve import _provision
    from qod_cli.serve_target import resolve

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    respx_mock.post(f"{BASE}/api/auth/login").mock(
        return_value=httpx.Response(200, json={"token": "jwt-1"})
    )
    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": []})
    )
    respx_mock.post(f"{BASE}/api/tenant/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": []})
    )
    respx_mock.post(f"{BASE}/api/database/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        return_value=httpx.Response(200, json={"pools": []})
    )
    respx_mock.post(f"{BASE}/api/pool/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/config/client").mock(
        return_value=httpx.Response(
            200, json={"flightSqlHost": None, "flightSqlPort": 31338, "flightSqlTls": True}
        )
    )

    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    lines = []
    _provision(
        manager_url=BASE, tenant="default", target=resolve(str(f), data_root=tmp_path),
        pool="bi", size=1, password="pw", profile="default", generated=True,
        ready_timeout=5, pg_port=25432, pg_data_dir=str(tmp_path / "pg"), echo=lines.append,
    )
    banner = "\n".join(lines)
    assert "jdbc:arrow-flight-sql://localhost:31338/" in banner
    assert "None" not in banner
