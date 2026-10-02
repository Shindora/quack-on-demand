package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Tenant, TenantAcl}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Try

class TenantAclStoreSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodopa")

  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodopa_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  "PostgresControlPlaneStore" should "round-trip TenantAcl through upsert and list" in
    withStore { store =>
      val acl = TenantAcl(
        mode = Some("opa"),
        opaUrl = Some("http://opa:8181"),
        opaPolicyPath = Some("acme/authz"),
        opaToken = Some("s3cret"),
        sendStatementText = true
      )
      store.upsertTenant(Tenant("acme", "Acme", acl = acl))
      store.listTenants().find(_.id == "acme").map(_.acl) shouldBe Some(acl)
      store.snapshot().tenants.find(_.id == "acme").map(_.acl) shouldBe Some(acl)
    }

  it should "default an untouched tenant to TenantAcl()" in withStore { store =>
    store.upsertTenant(Tenant("globex"))
    store.listTenants().find(_.id == "globex").map(_.acl) shouldBe Some(TenantAcl())
  }

  private val opaAcl = TenantAcl(
    mode = Some("opa"),
    opaUrl = Some("http://opa:8181"),
    opaPolicyPath = Some("acme/authz"),
    opaToken = Some("s3cret"),
    sendStatementText = true
  )

  it should "never let a full-row upsert of an existing tenant change its acl" in withStore {
    store =>
      store.upsertTenant(Tenant("acme", "Acme"))
      store.updateTenantAcl("acme", opaAcl)
      // A concurrent setTenantAuth / setTenantDisabled read the row BEFORE the acl write and
      // upserts its stale copy: the acl must survive, the other columns must land.
      store.upsertTenant(Tenant("acme", "Acme Corp", disabled = true, acl = TenantAcl()))
      val row = store.listTenants().find(_.id == "acme").get
      row.acl shouldBe opaAcl
      row.displayName shouldBe "Acme Corp"
      row.disabled shouldBe true
  }

  it should "write the acl of a NEW tenant on insert" in withStore { store =>
    store.upsertTenant(Tenant("initech", acl = opaAcl))
    store.listTenants().find(_.id == "initech").map(_.acl) shouldBe Some(opaAcl)
  }

  it should "change only the acl columns with updateTenantAcl" in withStore { store =>
    store.upsertTenant(
      Tenant(
        "acme",
        "Acme",
        disabled = true,
        authProvider = "keycloak",
        authConfig = Map("k" -> "v")
      )
    )
    store.updateTenantAcl("acme", opaAcl)
    val row = store.listTenants().find(_.id == "acme").get
    row shouldBe Tenant(
      "acme",
      "Acme",
      disabled = true,
      authProvider = "keycloak",
      authConfig = Map("k" -> "v"),
      acl = opaAcl
    )
    store.updateTenantAcl("acme", TenantAcl())
    store.listTenants().find(_.id == "acme").get.acl shouldBe TenantAcl()
  }
