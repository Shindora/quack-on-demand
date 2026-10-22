from qod_cli.fix_actions import action_lines


def test_no_actions_renders_nothing():
    assert action_lines(None) == [] and action_lines([]) == [] and action_lines("x") == []
    assert action_lines([{"where": "manager"}]) == []


def test_non_string_command_is_skipped():
    assert action_lines([{"where": "manager", "command": 1}]) == []


def test_single_action_names_where_and_note():
    assert action_lines([{"where": "manager", "command": "qod fleet drain a", "note": "then undrain"}]) == [
        "to fix:",
        "  on the manager:  qod fleet drain a  (then undrain)",
    ]


def test_alternatives_align_and_name_the_server():
    lines = action_lines([{"where": "server", "command": "qod fleet join ... --name <another-name>"},
                          {"where": "manager", "command": "qod fleet remove a"}], server="a")
    assert lines == [
        "to fix (any one):",
        "  on a:            qod fleet join ... --name <another-name>",
        "  on the manager:  qod fleet remove a",
    ]


def test_server_action_without_a_name_says_the_server():
    assert action_lines([{"where": "server", "command": "x"}]) == ["to fix:", "  on the server:  x"]
