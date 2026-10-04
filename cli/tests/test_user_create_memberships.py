import json

import httpx

from qod_cli.main import app

BASE = "http://localhost:20900"


def _route(respx_mock):
    return respx_mock.post(f"{BASE}/api/user/create").mock(
        return_value=httpx.Response(200, json={"id": "u1"})
    )


def test_create_sends_repeated_role_and_group(runner, respx_mock):
    route = _route(respx_mock)
    r = runner.invoke(
        app,
        ["user", "create", "--tenant", "acme", "--username", "bob", "--password", "pw",
         "--role", "analyst", "--role", "qod_no_tables", "--group", "qod_no_pools"],
    )
    assert r.exit_code == 0, r.output
    body = json.loads(route.calls.last.request.content)
    assert body["roles"] == ["analyst", "qod_no_tables"]
    assert body["groups"] == ["qod_no_pools"]


def test_create_omits_lists_when_flags_absent(runner, respx_mock):
    route = _route(respx_mock)
    r = runner.invoke(
        app, ["user", "create", "--tenant", "acme", "--username", "bob", "--password", "pw"]
    )
    assert r.exit_code == 0, r.output
    body = json.loads(route.calls.last.request.content)
    assert "roles" not in body and "groups" not in body


def test_superuser_rejects_role_flag(runner, respx_mock):
    route = _route(respx_mock)
    r = runner.invoke(
        app, ["user", "create", "--superuser", "--username", "root", "--password", "pw", "--role", "x"]
    )
    assert r.exit_code == 2
    assert not route.called


def test_superuser_rejects_group_flag(runner, respx_mock):
    route = _route(respx_mock)
    r = runner.invoke(
        app, ["user", "create", "--superuser", "--username", "root", "--password", "pw", "--group", "g"]
    )
    assert r.exit_code == 2
    assert not route.called
