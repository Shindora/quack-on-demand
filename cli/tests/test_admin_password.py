from qod_cli import admin_password as ap
from qod_cli.config import load_start_env, save_start_env

PG = {"host": "h", "port": "5432", "user": "u", "password": "p", "dbname": "qod"}


class FakeConn:
    def __init__(self, table=True, rows=()):
        self.table, self.rows, self.closed = table, list(rows), False

    def run(self, sql, **params):
        if "to_regclass" in sql:
            return [[self.table]]
        self.params = params
        return self.rows

    def close(self):
        self.closed = True


def test_admin_usernames_defaults_and_splits():
    assert ap.admin_usernames({}) == ["admin@localhost.local", "admin"]
    assert ap.admin_usernames({"QOD_ADMIN_USERNAME": " ops , root ,"}) == ["ops", "root"]


def test_state_present_when_a_superuser_row_exists():
    conn = FakeConn(rows=[[1]])
    assert ap.control_plane_admin_state(PG, ["admin"], connect=lambda **kw: conn) == ap.PRESENT
    assert conn.params == {"names": ["admin"]}
    assert conn.closed


def test_state_absent_without_the_table_or_the_row():
    assert (
        ap.control_plane_admin_state(PG, ["a"], connect=lambda **kw: FakeConn(table=False))
        == ap.ABSENT
    )
    assert (
        ap.control_plane_admin_state(PG, ["a"], connect=lambda **kw: FakeConn(rows=[]))
        == ap.ABSENT
    )


def test_state_absent_when_the_database_is_missing():
    def connect(**kw):
        raise Exception({"C": "3D000", "M": "database does not exist"})

    assert ap.control_plane_admin_state(PG, ["a"], connect=connect) == ap.ABSENT


def test_state_unreachable_on_any_other_connect_error():
    def connect(**kw):
        raise OSError("connection refused")

    assert ap.control_plane_admin_state(PG, ["a"], connect=connect) == ap.UNREACHABLE


def test_first_boot_password_order():
    assert ap.first_boot_password("from-env", True, prompt=lambda: "typed") == "from-env"
    assert ap.first_boot_password(None, True, prompt=lambda: "typed") == "typed"
    assert ap.first_boot_password(None, False, prompt=lambda: "typed") is None


def test_refusal_names_both_remedies():
    msg = ap.refusal("qod start")
    assert "run qod start in a terminal" in msg and "export QOD_ADMIN_PASSWORD" in msg


def test_migration_removes_the_stored_value_and_prints_it_once():
    save_start_env({"QOD_ADMIN_PASSWORD": "legacy", "QOD_PG_HOST": "db"})
    lines = []
    ap.migrate_stored_password(lines.append)
    assert "QOD_ADMIN_PASSWORD" not in load_start_env()
    assert load_start_env()["QOD_PG_HOST"] == "db"
    assert "Your current admin password is: legacy" in lines[0]
    lines.clear()
    ap.migrate_stored_password(lines.append)
    assert lines == []
