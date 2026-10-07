package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import scala.util.Try

/** Revocation-that-also-kills contract on [[PatHandlers.revoke]]: a successful revoke passes the
  * FULL cascaded id set to the injected kill and broadcast functions, reports the local kill count,
  * and audits once with both counts; a failed revoke touches neither function.
  */
class PatRevokeKillSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodprk")

  private def withFreshDb(test: (UserStore, PatStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodprk_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val pats  = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(users, pats)
      finally
        pats.close()
        users.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def seedUser(users: UserStore): String =
    users.upsertUser(None, "alice", "pw", "admin")
    users.userIdOf(None, "alice").get

  it should "kill and broadcast the full cascaded id set and audit both counts" in
    withFreshDb { (users, pats) =>
      val uid           = seedUser(users)
      val (root, raw)   = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)
      val (child, _)    = pats.mint(uid, "child", TokenRestriction.Unrestricted, Some(root.id), 1)
      val (grand, _)    = pats.mint(uid, "grand", TokenRestriction.Unrestricted, Some(child.id), 2)
      val killedSets    = scala.collection.mutable.ListBuffer.empty[Set[String]]
      val broadcastSets = scala.collection.mutable.ListBuffer.empty[Set[String]]
      val auditStore    = new RecordingTelemetryStore
      val h             = new PatHandlers(
        pats,
        new SessionTokenStore(),
        userOf = (_, _) => None,
        audit = new AuditRecorder(auditStore, _ => None),
        killStatements = ids => {
          // The kill runs only after the cascade has committed -- see the load-bearing ordering
          // comment on PatHandlers.revoke.
          pats.findById(uid, child.id).get.revokedAt shouldNot be(empty)
          killedSets += ids
          2
        },
        broadcastKill = ids => { broadcastSets += ids; () }
      )
      val out = h.revoke(Some(raw), PatRevokeRequest(child.id)).unsafeRunSync()
      out shouldBe Right(PatRevokeResponse("ok", 2))
      killedSets.toList shouldBe List(Set(child.id, grand.id))
      broadcastSets.toList shouldBe List(Set(child.id, grand.id))
      val revokes = auditStore.events.filter(_.action == AuditActions.AuthPatRevoke)
      revokes.map(_.outcome) shouldBe List("ok")
      revokes.head.detail.get("revokedCount") shouldBe Some("2")
      revokes.head.detail.get("killedStatements") shouldBe Some("2")
      revokes.head.detail.get("killFailed") shouldBe Some("false")
      revokes.head.detail.get("broadcastFailed") shouldBe Some("false")
    }

  it should "neither kill nor broadcast on a failed revoke" in
    withFreshDb { (users, pats) =>
      val uid       = seedUser(users)
      val (_, raw)  = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)
      var killCalls = 0
      var castCalls = 0
      val h         = new PatHandlers(
        pats,
        new SessionTokenStore(),
        userOf = (_, _) => None,
        killStatements = _ => { killCalls += 1; 0 },
        broadcastKill = _ => castCalls += 1
      )
      val out = h.revoke(Some(raw), PatRevokeRequest("pat-does-not-exist")).unsafeRunSync()
      out.isLeft shouldBe true
      killCalls shouldBe 0
      castCalls shouldBe 0
    }

  it should "still answer ok and audit once when the kill and broadcast functions both throw" in
    withFreshDb { (users, pats) =>
      val uid         = seedUser(users)
      val (root, raw) = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)
      val (child, _)  = pats.mint(uid, "child", TokenRestriction.Unrestricted, Some(root.id), 1)
      val auditStore  = new RecordingTelemetryStore
      val h           = new PatHandlers(
        pats,
        new SessionTokenStore(),
        userOf = (_, _) => None,
        audit = new AuditRecorder(auditStore, _ => None),
        killStatements = _ => throw new RuntimeException("kill boom"),
        broadcastKill = _ => throw new RuntimeException("cast boom")
      )
      val out = h.revoke(Some(raw), PatRevokeRequest(child.id)).unsafeRunSync()
      out shouldBe Right(PatRevokeResponse("ok", 0))
      val revokes = auditStore.events.filter(_.action == AuditActions.AuthPatRevoke)
      revokes.map(_.outcome) shouldBe List("ok")
      revokes.head.detail.get("killedStatements") shouldBe Some("0")
      revokes.head.detail.get("killFailed") shouldBe Some("true")
      revokes.head.detail.get("broadcastFailed") shouldBe Some("true")
      pats.findById(uid, child.id).get.revokedAt shouldNot be(empty)
      pats.findById(uid, root.id).get.revokedAt shouldBe empty
    }

  it should "neither kill nor broadcast on an empty cascade and audit the denial" in
    withFreshDb { (users, pats) =>
      val uid         = seedUser(users)
      val (root, raw) = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)
      val (child, _)  = pats.mint(uid, "child", TokenRestriction.Unrestricted, Some(root.id), 1)
      val auditStore  = new RecordingTelemetryStore
      var killCalls   = 0
      var castCalls   = 0
      val h           = new PatHandlers(
        pats,
        new SessionTokenStore(),
        userOf = (_, _) => None,
        audit = new AuditRecorder(auditStore, _ => None),
        killStatements = _ => { killCalls += 1; 0 },
        broadcastKill = _ => castCalls += 1
      )
      h.revoke(Some(raw), PatRevokeRequest(child.id)).unsafeRunSync().isRight shouldBe true
      killCalls shouldBe 1
      castCalls shouldBe 1
      // The child is still in the caller's subtree (isInSubtree ignores revocation), but the
      // second revoke cascades over nothing.
      val out = h.revoke(Some(raw), PatRevokeRequest(child.id)).unsafeRunSync()
      out.left.map(_._1) shouldBe Left(StatusCode.NotFound)
      killCalls shouldBe 1
      castCalls shouldBe 1
      auditStore.events
        .filter(_.action == AuditActions.AuthPatRevoke)
        .map(_.outcome) shouldBe List("ok", "denied")
    }
