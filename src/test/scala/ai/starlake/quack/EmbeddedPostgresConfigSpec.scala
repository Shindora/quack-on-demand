package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class EmbeddedPostgresConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  "EmbeddedPostgresConfig" should "default to off in application.conf" in:
    val cfg = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
    cfg.embeddedPostgres.enabled shouldBe false
    cfg.embeddedPostgres.port shouldBe 25432
    cfg.embeddedPostgres.dataDir shouldBe ""

  it should "honor camelCase overlays" in:
    val cfg = ConfigSource
      .string("""quack-on-demand { embeddedPostgres { enabled = true, port = 26000 } }""")
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.embeddedPostgres.enabled shouldBe true
    cfg.embeddedPostgres.port shouldBe 26000

  it should "refuse an out-of-range port" in:
    an[IllegalArgumentException] should be thrownBy EmbeddedPostgresConfig(port = 0)
    an[IllegalArgumentException] should be thrownBy EmbeddedPostgresConfig(port = 70000)
