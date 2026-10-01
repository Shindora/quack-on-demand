# Starter policy for QoD's OPA authorization (package path qod/authz, the default
# opaPolicyPath). QoD queries two rules:
#   POST /v1/data/qod/authz/connect    pool access, once per session handshake
#   POST /v1/data/qod/authz/statement  table access, once per statement
# Each must evaluate to an object {"allow": true} or {"allow": false, "reason": "..."}.
# Anything else (undefined rule, non-boolean allow, row_filter / masks) is a deny.
#
# This policy reproduces QoD's own grant semantics from data you load into OPA:
#   data.qod.pool_grants[tenant][role]  = ["bi", "*"]
#       pool names the role may connect to, "*" = every pool of the tenant
#   data.qod.table_grants[tenant][role] = [{"catalog": "tpch", "schema": "*", "table": "*", "verb": "RO"}]
#       "*" matches any catalog / schema / table
# Verbs: RO covers read; RW covers read and write; DDL covers ddl; ALL covers everything.
# Access names in the input are lowercase; grant names are compared lowercased.
package qod.authz

import rego.v1

# ---------------------------------------------------------------- connect

default connect := {"allow": false, "reason": "no pool grant"}

connect := {"allow": true} if {
	some role in input.user.roles
	some p in data.qod.pool_grants[input.tenant][role]
	pool_matches(p)
}

pool_matches(p) if p == "*"

pool_matches(p) if p == input.pool

# A branch pool (__br_<id8>) inherits connect permission from any pool of its
# parent database: input.parentPool lists them, and is null for a regular pool.
pool_matches(p) if {
	is_array(input.parentPool)
	p in input.parentPool
}

# ---------------------------------------------------------------- statement

default statement := {"allow": false, "reason": "no table grant"}

# The two rules below are mutually exclusive (count == 0 vs count > 0), so the
# complete rule `statement` can never produce two different values.
statement := {"allow": true} if count(denied) == 0

statement := {
	"allow": false,
	"reason": "missing table grants",
	"denied": [a | some a in denied],
} if count(denied) > 0

denied contains a if {
	some a in input.accesses
	not covered(a)
}

covered(a) if {
	some role in input.user.roles
	some g in data.qod.table_grants[input.tenant][role]
	wild(g.catalog, a.catalog)
	wild(g.schema, a.schema)
	wild(g.table, a.table)
	verb_covers(upper(g.verb), a.verb)
}

wild(g, _) if g == "*"

wild(g, v) if lower(g) == v

verb_covers(g, _) if g == "ALL"

verb_covers(g, v) if {
	g == "RO"
	v == "read"
}

verb_covers(g, v) if {
	g == "RW"
	v in {"read", "write"}
}

verb_covers(g, v) if {
	g == "DDL"
	v == "ddl"
}
