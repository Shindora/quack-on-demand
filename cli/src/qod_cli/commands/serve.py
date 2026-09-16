"""`qod serve <target>`: one command from your own data to a queryable gateway.

Boots a manager on a PERSISTENT embedded Postgres (QOD_PG_EMBEDDED, see
ai.starlake.quack.boot.EmbeddedControlPlane) so there is no external prerequisite,
then provisions a tenant, database, and pool around whatever the target points at
and prints the client connection strings.

Not the demo. `qod start --demo` is ephemeral, seeded with TPC-H, and deliberately
insecure (its posture comes from DemoConfig.overlay and lives only on that code
path). `qod serve` is persistent and keeps the normal secure posture: TLS on, DB
auth on, ACL on, a generated admin password instead of 'admin'.

Provisioning runs in a thread beside the output relay because the manager has to
be up before the REST calls can land, and `_run_supervised` owns the foreground
for the JVM's lifetime. Every provisioning step is ensure-semantics, so a failed
or interrupted run is resumed by simply re-running the command.
"""

from __future__ import annotations

import os
import secrets
import threading
from pathlib import Path
from urllib.parse import urlparse

import typer

from .. import launcher
from ..config import Settings, load_start_env, save_profile, save_start_env
from ..rest import ApiError, RestClient
from ..serve_provision import (
    ProvisionError,
    ensure_database,
    ensure_pool,
    ensure_tenant,
    wait_ready,
)
from ..serve_target import TargetError, composed_db_name
from ..serve_target import resolve as resolve_target
from ._launch import _exec, resolve_jar, resolve_java

# The seeded superuser. QOD_ADMIN_USERNAME defaults to "admin@localhost.local,admin",
# so both names exist; the short one is what a person types.
_ADMIN_USER = "admin"


def _resolve_admin_password() -> tuple[str, bool]:
    """(password, generated_now).

    Precedence is the CLI's usual one: a real QOD_ADMIN_PASSWORD wins, then the
    value a previous `qod serve` (or `qod setup`) stored, and only a completely
    fresh install generates. Reusing the stored value is what makes the password
    printed on the first serve keep working on every later one.
    """
    from_env = os.environ.get("QOD_ADMIN_PASSWORD")
    if from_env:
        return from_env, False
    stored = load_start_env().get("QOD_ADMIN_PASSWORD")
    if stored:
        return stored, False
    return secrets.token_urlsafe(12), True


def _object_store(
    access_key_id: str | None, secret_access_key: str | None,
    region: str | None, endpoint: str | None,
) -> dict:
    """s3_* keys per ObjectStoreSecret's vocabulary (the same one the admin UI's
    DataPathEditor writes). Flags win; otherwise the ambient AWS_* environment, so
    `qod serve s3://...` works in a shell that already has credentials."""
    out: dict = {}
    key = access_key_id or os.environ.get("AWS_ACCESS_KEY_ID")
    secret = secret_access_key or os.environ.get("AWS_SECRET_ACCESS_KEY")
    reg = region or os.environ.get("AWS_REGION") or os.environ.get("AWS_DEFAULT_REGION")
    if key:
        out["s3_access_key_id"] = key
    if secret:
        out["s3_secret_access_key"] = secret
    if reg:
        out["s3_region"] = reg
    if endpoint:
        out["s3_endpoint"] = endpoint
    return out


def _banner(
    *, tenant: str, db: str, pool: str, size: int, password: str, generated: bool,
    edge_host: str, edge_port: int, manager_url: str, pg_port: int, pg_data_dir: str,
    description: str,
) -> str:
    """The connect snippet. The password line appears ONLY on the run that
    generated it: reprinting a stored secret on every boot would put it in every
    terminal scrollback and CI log for no benefit."""
    from ..config import config_path

    jdbc = (
        f"jdbc:arrow-flight-sql://{edge_host}:{edge_port}/"
        f"?tenant={tenant}&pool={pool}&user={_ADMIN_USER}"
        "&useEncryption=true&disableCertificateVerification=true"
    )
    lines = [
        "",
        f"  control plane : embedded postgres ({pg_data_dir}, port {pg_port})",
        f"  serving       : {description}",
        f"  tenant/db/pool: {tenant} / {db} / {pool}  ({size} dual node)",
        f"  admin         : {_ADMIN_USER}",
    ]
    if generated:
        lines += [
            f"  password      : {password}   (generated, shown once)",
            f"                  stored in {config_path()}",
        ]
    lines += [
        "",
        f"  JDBC {jdbc}",
        f"  UI   {manager_url.rstrip('/')}/ui/",
        "",
        f"  add a user     : qod user create --tenant {tenant} --username alice --password ...",
        "  serve more data: qod serve ./other.duckdb",
        f"  rotate admin   : qod user update --username {_ADMIN_USER} --password ...",
        "  Ctrl-C to stop.",
        "",
    ]
    return "\n".join(lines)


def _provision(
    *, manager_url: str, tenant: str, target, pool: str, size: int, password: str,
    profile: str, generated: bool, ready_timeout: float, pg_port: int, pg_data_dir: str,
    echo,
) -> None:
    """Wait for the manager, log in, ensure tenant/database/pool, persist the
    session, print the banner.

    Never raises: this runs on a background thread whose exception would be
    invisible, and the manager must stay up either way so the user can read the
    relayed log and re-run. Failures are reported with the equivalent manual
    command.
    """
    settings = Settings(manager_url=manager_url)
    client = RestClient(settings)
    try:
        wait_ready(client, timeout_s=ready_timeout)
        login = client.request(
            "POST", "/api/auth/login", body={"username": _ADMIN_USER, "password": password}
        )
        settings.token = login["token"]
        client = RestClient(settings)
        ensure_tenant(client, tenant)
        ensure_database(client, tenant, target)
        db_full = composed_db_name(tenant, target.name)
        ensure_pool(client, tenant, db_full, pool, size)
        edge = client.request("GET", "/api/config/client") or {}
        edge_host = edge.get("flightSqlHost", "")
        if edge_host in ("", "0.0.0.0"):
            edge_host = urlparse(manager_url).hostname or "localhost"
        edge_port = int(edge.get("flightSqlPort", 31338))
        save_profile(
            profile,
            {
                "manager_url": manager_url,
                "token": login["token"],
                "sql_user": _ADMIN_USER,
                "tenant": tenant,
                "pool": pool,
                "edge_host": edge_host,
                "edge_port": edge_port,
                "edge_tls": edge.get("flightSqlTls", True),
            },
        )
        echo(
            _banner(
                tenant=tenant, db=db_full, pool=pool, size=size, password=password,
                generated=generated, edge_host=edge_host, edge_port=edge_port,
                manager_url=manager_url, pg_port=pg_port, pg_data_dir=pg_data_dir,
                description=target.description,
            )
        )
    except ProvisionError as exc:
        echo(f"\nqod serve: {exc.step} failed: {exc.detail}")
        if exc.manual:
            echo(f"  finish by hand: {exc.manual}")
        echo("  the manager is still running; re-run qod serve to resume, or Ctrl-C to stop.")
    except ApiError as exc:
        echo(f"\nqod serve: provisioning failed: {exc}")
        echo("  the manager is still running; re-run qod serve to resume, or Ctrl-C to stop.")


def _spawn_provisioning(**kwargs) -> None:
    """Seam: tests replace this so no thread polls a manager that never boots."""
    thread = threading.Thread(target=_provision, kwargs=kwargs, daemon=True)
    thread.start()


def serve(
    ctx: typer.Context,
    target: str = typer.Argument(
        None,
        help="What to serve: a .duckdb file, a parquet/csv file, a directory or glob of them, "
        "an s3://, gs:// or az:// prefix, or nothing for a fresh empty DuckLake.",
    ),
    tenant: str = typer.Option("default", "--tenant", help="Tenant to provision under."),
    name: str = typer.Option(None, "--name", help="Database name; default is derived from TARGET."),
    pool: str = typer.Option("bi", "--pool", help="Pool name."),
    schema: str = typer.Option("main", "--schema", help="Schema inside a .duckdb file."),
    kind: str = typer.Option(
        None, "--kind",
        help="Override the inferred shape for a directory or a remote prefix: pass 'ducklake' "
        "when it holds a DuckLake's data files rather than loose parquet.",
    ),
    size: int = typer.Option(1, "--size", help="Nodes in the pool. A duckdb-file must stay at 1."),
    table: list[str] = typer.Option(
        [], "--table", metavar="NAME=GLOB",
        help="Explicit view for a multi-table remote layout. Repeatable.",
    ),
    access_key_id: str = typer.Option(None, "--access-key-id", help="Object-store key id."),
    secret_access_key: str = typer.Option(None, "--secret-access-key", help="Object-store secret."),
    region: str = typer.Option(None, "--region", help="Object-store region."),
    endpoint: str = typer.Option(None, "--endpoint", help="S3-compatible endpoint (e.g. MinIO)."),
    pg_port: int = typer.Option(25432, "--pg-port", help="Embedded Postgres port."),
    pg_data_dir: str = typer.Option(
        None, "--pg-data-dir", help="Embedded Postgres data dir; default <data-dir>/pg."
    ),
    ready_timeout: float = typer.Option(
        180.0, "--ready-timeout", help="Seconds to wait for the manager before giving up."
    ),
    version: str = typer.Option(
        None, "--version", envvar="QOD_VERSION", help="Manager release to run."
    ),
    jar: Path = typer.Option(None, "--jar", help="Run this local jar instead of downloading."),
):
    """Serve your own data through a fresh, persistent gateway in one command.

    Provisions tenant/database/pool around TARGET on an embedded Postgres, so
    nothing external is required. Re-running is safe: every step creates only what
    is missing, so `qod serve ./other.duckdb` adds a second database beside the
    first. Ctrl-C tears the manager and its nodes down gracefully.
    """
    try:
        resolved = resolve_target(
            target,
            kind=kind,
            name=name,
            schema=schema,
            tables=list(table),
            object_store=_object_store(access_key_id, secret_access_key, region, endpoint),
            data_root=launcher.default_data_dir(),
        )
    except TargetError as exc:
        typer.echo(f"error: {exc}", err=True)
        raise typer.Exit(1)

    # Mirrors Names.normalizeTenantDbName's refusal: DuckDB cannot attach a catalog
    # under the name of an existing one. Caught here so the failure costs no JVM boot.
    if resolved.name.lower() == tenant.lower():
        typer.echo(
            f"error: the database name {resolved.name!r} would equal the tenant name, which "
            "DuckDB refuses (a catalog cannot shadow an existing one). Pass --name.",
            err=True,
        )
        raise typer.Exit(1)

    java = resolve_java()
    jar_path = jar.resolve() if jar is not None else resolve_jar(version)

    app_home = launcher.default_cache_dir()
    try:
        duckdb_bin = launcher.ensure_duckdb_cli(app_home)
        libduckdb = launcher.ensure_libduckdb(app_home)
    except Exception as exc:
        typer.echo(f"could not provision duckdb: {exc}", err=True)
        raise typer.Exit(1)
    spawn_sh, spawn_ps1 = launcher.materialize_spawn_scripts(app_home / "scripts")

    state_dir = launcher.default_data_dir()
    state_dir.mkdir(parents=True, exist_ok=True)
    resolved_pg_dir = pg_data_dir or str(state_dir / "pg")

    password, generated = _resolve_admin_password()
    if generated:
        # Persisted BEFORE the manager boots: the seeded password must survive a
        # restart, or the banner's credentials stop working on the second run.
        save_start_env({"QOD_ADMIN_PASSWORD": password})

    base_env = {**load_start_env(), **os.environ}
    env = launcher.runtime_env(
        base_env, app_home, duckdb_bin, spawn_sh, spawn_ps1, libduckdb_lib=libduckdb
    )
    env.setdefault("QOD_DUCKLAKE_DATA_PATH", str(state_dir / "ducklake" / "data"))
    env["QOD_PG_EMBEDDED"] = "true"
    env["QOD_PG_EMBEDDED_PORT"] = str(pg_port)
    env["QOD_PG_EMBEDDED_DATA_DIR"] = resolved_pg_dir
    env["QOD_ADMIN_PASSWORD"] = password
    # quack-on-demand.acl.enabled defaults to FALSE, so a persistent install has to
    # ask for it. TLS and DB auth are already on by default. A real env var wins.
    env.setdefault("QOD_ACL_ENABLED", "true")

    _spawn_provisioning(
        manager_url=ctx.obj.settings.manager_url,
        tenant=tenant,
        target=resolved,
        pool=pool,
        size=size,
        password=password,
        profile=ctx.obj.profile,
        generated=generated,
        ready_timeout=ready_timeout,
        pg_port=pg_port,
        pg_data_dir=resolved_pg_dir,
        echo=lambda line: typer.echo(line, err=True),
    )

    os.chdir(state_dir)
    _exec(
        launcher.build_jar_command(
            java, str(jar_path), list(ctx.args), java_opts=env.get("JAVA_OPTS")
        ),
        env,
    )
