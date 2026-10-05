"""The seeded admin password's lifecycle on the CLI side.

The password is chosen once, at the first boot of a control plane, and never
written to disk: `qod start` / `qod serve` pass it to the manager through its
environment on that boot only, and the manager seeds the admin row
insert-only. Later changes go through `qod auth change-password`; a lost
password through `qod admin reset-password`.
"""

from __future__ import annotations

import sys

import typer

from .config import config_path, load_start_env, save_start_env

KEY = "QOD_ADMIN_PASSWORD"
DEFAULT_ADMIN_USERNAMES = "admin@localhost.local,admin"

PRESENT = "present"
ABSENT = "absent"
UNREACHABLE = "unreachable"


def admin_usernames(env: dict) -> list[str]:
    raw = env.get("QOD_ADMIN_USERNAME") or DEFAULT_ADMIN_USERNAMES
    return [name.strip() for name in raw.split(",") if name.strip()]


def is_interactive() -> bool:
    """Seam: CliRunner rebinds stdin per invoke, so tests patch this instead."""
    return sys.stdin.isatty()


def prompt_new_password(label: str = "Admin password") -> str:
    while True:
        value = typer.prompt(label, hide_input=True, confirmation_prompt=True)
        if value.strip():
            return value
        typer.echo("the password cannot be empty", err=True)


def first_boot_password(env_value, interactive: bool, prompt=prompt_new_password):
    """A shell-exported value first, else a prompt on a terminal, else None
    (the caller refuses to start)."""
    if env_value:
        return env_value
    if interactive:
        return prompt()
    return None


def refusal(command: str) -> str:
    return (
        f"error: first boot needs an admin password; run {command} in a terminal or "
        f"export {KEY}"
    )


def _sqlstate(exc: Exception):
    arg = exc.args[0] if exc.args else None
    return arg.get("C") if isinstance(arg, dict) else None


def control_plane_admin_state(pg: dict, usernames: list[str], connect=None) -> str:
    """PRESENT when one of `usernames` has a superuser row, ABSENT when the
    database, the table or every row is missing (first boot), UNREACHABLE on
    any other failure (the manager's own preflight reports it)."""
    if connect is None:
        import pg8000.native

        connect = pg8000.native.Connection
    try:
        conn = connect(
            user=pg["user"],
            password=pg["password"],
            host=pg["host"],
            port=int(pg["port"]),
            database=pg["dbname"],
            timeout=5,
        )
    except Exception as exc:  # noqa: BLE001 - classify every connect failure
        return ABSENT if _sqlstate(exc) == "3D000" else UNREACHABLE
    try:
        if not conn.run("SELECT to_regclass('qodstate_user') IS NOT NULL")[0][0]:
            return ABSENT
        rows = conn.run(
            "SELECT 1 FROM qodstate_user WHERE tenant IS NULL AND username = ANY(:names)",
            names=list(usernames),
        )
        return PRESENT if rows else ABSENT
    except Exception:  # noqa: BLE001
        return UNREACHABLE
    finally:
        conn.close()


def migrate_stored_password(echo) -> None:
    """Remove a QOD_ADMIN_PASSWORD an earlier CLI stored in the [start]
    table, printing it once: for `qod serve` installs that file was the only
    copy, and every earlier boot wrote it onto the admin row."""
    stored = load_start_env().get(KEY)
    if not stored:
        return
    save_start_env({}, remove=[KEY])
    echo(
        f"removed {KEY} from {config_path()}: the admin password is no longer stored.\n"
        f"  Your current admin password is: {stored}\n"
        "  Save it now; to change it use qod auth change-password."
    )
