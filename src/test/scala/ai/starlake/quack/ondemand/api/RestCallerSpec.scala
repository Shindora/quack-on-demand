package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** The executor identity a REST catalog handler runs a statement under. Regression anchor: a PAT
  * used to fall through the JWT-only lookup to the synthetic superuser.
  */
class RestCallerSpec extends AnyFlatSpec with Matchers:

  import IdentityFixtures.*

  private val resolver = RestCaller(Some(StaticKey), sessionOf, patOf)

  private def shape(r: Either[(StatusCode, ErrorResponse), ExecCaller]) =
    r.map(c => (c.connectionId, c.identity, c.restriction, c.patId))

  "RestCaller" should "run the static key as the unrestricted superuser" in {
    shape(resolver("conn", Some(StaticKey))) shouldBe
      Right(
        ("conn", CatalogPreviewHandlers.SuperuserIdentity, TokenRestriction.Unrestricted, None)
      )
  }

  it should "treat an absent credential as the static key (MCP static principal, guard-admitted)" in {
    shape(resolver("conn", None)) shouldBe
      Right(
        ("conn", CatalogPreviewHandlers.SuperuserIdentity, TokenRestriction.Unrestricted, None)
      )
  }

  it should "run a session JWT as its user, unrestricted" in {
    shape(resolver("conn", Some(SessionTok))) shouldBe
      Right(("conn", "alice", TokenRestriction.Unrestricted, None))
  }

  it should "run a PAT as its owner with the PAT's restriction and id" in {
    shape(resolver("conn", Some(PatTok))) shouldBe
      Right(("conn", "alice", PatRestriction, Some(PatId)))
  }

  it should "refuse an unknown, expired or revoked token with 401, never superuser" in
    List("qod_pat_unknown", "garbage", "").foreach { t =>
      resolver("conn", Some(t)).left.toOption.map(e => (e._1, e._2.error)) shouldBe
        Some((StatusCode.Unauthorized, "unauthorized"))
    }

  it should "match the static key exactly (no prefix, suffix or case variant)" in
    List(StaticKey + "x", StaticKey.dropRight(1), StaticKey.toUpperCase, " " + StaticKey)
      .foreach { t =>
        resolver("conn", Some(t)).isLeft shouldBe true
      }

  it should "never match an empty configured static key" in {
    val r = RestCaller(Some(""), sessionOf, patOf)
    r("conn", Some("")).isLeft shouldBe true
  }

  it should "refuse every token when only the static key is known" in {
    RestCaller.staticOnly("conn", Some(PatTok)).isLeft shouldBe true
    RestCaller.staticOnly("conn", Some(SessionTok)).isLeft shouldBe true
  }
