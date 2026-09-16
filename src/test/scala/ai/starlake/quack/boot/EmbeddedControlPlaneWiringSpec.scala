package ai.starlake.quack.boot

import ai.starlake.quack.{EmbeddedPostgresConfig, Main, ManagerConfig}
import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.file.Files

class EmbeddedControlPlaneWiringSpec extends AnyFlatSpec with Matchers:
  import Main.given

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private def base: ManagerConfig =
    ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]

  "withEmbeddedControlPlane" should "pass the config through untouched when disabled" in:
    val cfg                         = base
    var seen: Option[ManagerConfig] = None
    Main
      .withEmbeddedControlPlane(cfg) { applied =>
        seen = Some(applied)
        cats.effect.IO.pure(ExitCode.Success)
      }
      .unsafeRunSync() shouldBe ExitCode.Success
    seen shouldBe Some(cfg)

  it should "inject live coordinates and stop the server afterwards when enabled" in:
    val dir  = Files.createTempDirectory("qod-embedded-wiring")
    val port = freePort()
    val cfg  = base.copy(embeddedPostgres =
      EmbeddedPostgresConfig(enabled = true, port = port, dataDir = dir.toString)
    )
    var seen: Option[ManagerConfig] = None
    Main
      .withEmbeddedControlPlane(cfg) { applied =>
        seen = Some(applied)
        cats.effect.IO.pure(ExitCode.Success)
      }
      .unsafeRunSync()
    seen.get.defaultMetastore.pgPort shouldBe port.toString
    seen.get.defaultMetastore.pgHost shouldBe "localhost"
    // Posture guardrail: nothing outside the five pg coordinates moved.
    seen.get.apiKey shouldBe cfg.apiKey
    seen.get.runtimeType shouldBe cfg.runtimeType
    seen.get.defaultMetastore.dataPath shouldBe cfg.defaultMetastore.dataPath
    // The port is free again, so stop() ran.
    val probe = new java.net.ServerSocket(port)
    probe.close()

  it should "stop the server even when the boot body fails" in:
    val dir  = Files.createTempDirectory("qod-embedded-wiring-fail")
    val port = freePort()
    val cfg  = base.copy(embeddedPostgres =
      EmbeddedPostgresConfig(enabled = true, port = port, dataDir = dir.toString)
    )
    an[RuntimeException] should be thrownBy
      Main
        .withEmbeddedControlPlane(cfg)(_ => cats.effect.IO.raiseError(new RuntimeException("boom")))
        .unsafeRunSync()
    val probe = new java.net.ServerSocket(port)
    probe.close()
