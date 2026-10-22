"""The "to fix" block of a manager error (`ErrorResponse.actions`), shared by every qod command
and by qod fleet join, so a fix reads the same wherever it is printed."""

from __future__ import annotations


def action_lines(actions, server: str | None = None) -> list[str]:
    """Lines for `actions` (a list of {where, command, note?}); [] when there is none to show.

    Several actions are alternatives (`to fix (any one):`); a multi-step fix is one action whose
    note names the steps after its command. `server` names the machine of a `where: server`
    action ("this server" from qod fleet join, the server's name elsewhere)."""
    if not isinstance(actions, list):
        return []
    rows = []
    for a in actions:
        if not isinstance(a, dict):
            continue
        command = a.get("command")
        if not isinstance(command, str) or not command:
            continue
        label = "on the manager:" if a.get("where") == "manager" else f"on {server or 'the server'}:"
        rows.append((label, command + (f"  ({a['note']})" if a.get("note") else "")))
    if not rows:
        return []
    width = max(len(label) for label, _ in rows)
    head = "to fix:" if len(rows) == 1 else "to fix (any one):"
    return [head] + [f"  {label.ljust(width)}  {text}" for label, text in rows]
