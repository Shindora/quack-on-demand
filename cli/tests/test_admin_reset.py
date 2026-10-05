import subprocess

import pytest

from qod_cli import launcher


@pytest.fixture
def jar_run(monkeypatch, tmp_path):
    from qod_cli.commands import admin as admin_cmd

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    calls = {}
    monkeypatch.setattr(admin_cmd, "resolve_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "default_data_dir", lambda: tmp_path / "state")

    def fake_run(cmd, input=None, text=None, env=None):
        calls.update(cmd=cmd, input=input, env=env)
        return subprocess.CompletedProcess(cmd, calls.get("rc", 0))

    monkeypatch.setattr(admin_cmd.subprocess, "run", fake_run)
    calls["jar"] = jar
    return calls


def _invoke(runner, calls, *args, input=None):
    from qod_cli.main import app

    return runner.invoke(
        app, ["admin", "reset-password", *args, "--jar", str(calls["jar"])], input=input
    )


def test_reset_pipes_the_password_on_stdin_never_argv(runner, jar_run):
    result = _invoke(runner, jar_run, "--username", "root", input="n3w\n")
    assert result.exit_code == 0, result.output
    assert jar_run["cmd"][-2:] == ["reset-password", "root"]
    assert "n3w" not in " ".join(jar_run["cmd"])
    assert jar_run["input"] == "n3w\n"


def test_reset_defaults_to_every_configured_admin_in_order(runner, jar_run, monkeypatch):
    monkeypatch.setenv("QOD_ADMIN_USERNAME", "ops, root")
    _invoke(runner, jar_run, input="pw\n")
    assert jar_run["cmd"][-3:] == ["reset-password", "ops", "root"]


def test_reset_defaults_to_both_builtin_admin_names(runner, jar_run, monkeypatch):
    monkeypatch.delenv("QOD_ADMIN_USERNAME", raising=False)
    _invoke(runner, jar_run, input="pw\n")
    assert jar_run["cmd"][-3:] == ["reset-password", "admin@localhost.local", "admin"]


def test_reset_username_option_resets_only_that_one(runner, jar_run, monkeypatch):
    monkeypatch.setenv("QOD_ADMIN_USERNAME", "ops,root")
    _invoke(runner, jar_run, "--username", "root", input="pw\n")
    assert jar_run["cmd"][-2:] == ["reset-password", "root"]


def test_reset_forwards_must_change(runner, jar_run):
    _invoke(runner, jar_run, "--must-change", input="pw\n")
    assert jar_run["cmd"][-1] == "--must-change"


def test_reset_picks_embedded_when_pgdata_exists_and_no_pg_host(runner, jar_run, tmp_path):
    (tmp_path / "state" / "pg" / "pgdata").mkdir(parents=True)
    result = _invoke(runner, jar_run, input="pw\n")
    assert jar_run["env"]["QOD_PG_EMBEDDED"] == "true"
    assert jar_run["env"]["QOD_PG_EMBEDDED_DATA_DIR"] == str(tmp_path / "state" / "pg")
    assert f"target: embedded control plane at {tmp_path / 'state' / 'pg'}" in result.output


def test_reset_external_when_pg_host_configured(runner, jar_run, tmp_path, monkeypatch):
    (tmp_path / "state" / "pg" / "pgdata").mkdir(parents=True)
    monkeypatch.setenv("QOD_PG_HOST", "db.internal")
    monkeypatch.setenv("QOD_PG_PORT", "6543")
    monkeypatch.setenv("QOD_PG_DBNAME", "cp")
    result = _invoke(runner, jar_run, input="pw\n")
    assert jar_run["env"]["QOD_PG_EMBEDDED"] == "false"
    assert "target: Postgres db.internal:6543/cp" in result.output


def test_reset_external_target_defaults(runner, jar_run, monkeypatch):
    for key in ("QOD_PG_HOST", "QOD_PG_PORT", "QOD_PG_DBNAME"):
        monkeypatch.delenv(key, raising=False)
    result = _invoke(runner, jar_run, input="pw\n")
    assert "target: Postgres localhost:5432/qod" in result.output


def test_reset_external_overrides_an_inherited_embedded_flag(
    runner, jar_run, tmp_path, monkeypatch
):
    (tmp_path / "state" / "pg" / "pgdata").mkdir(parents=True)
    monkeypatch.setenv("QOD_PG_EMBEDDED", "true")
    _invoke(runner, jar_run, "--external", input="pw\n")
    assert jar_run["env"]["QOD_PG_EMBEDDED"] == "false"


def test_reset_embedded_without_pgdata_refuses(runner, jar_run):
    result = _invoke(runner, jar_run, "--embedded", input="pw\n")
    assert result.exit_code == 1
    assert "nothing to reset; run qod serve first" in result.output
    assert "cmd" not in jar_run


def test_reset_refuses_an_empty_password(runner, jar_run):
    result = _invoke(runner, jar_run, input="\n")
    assert result.exit_code == 1
    assert "cmd" not in jar_run


def test_reset_propagates_the_jar_exit_code(runner, jar_run):
    jar_run["rc"] = 2
    result = _invoke(runner, jar_run, input="pw\n")
    assert result.exit_code == 2
