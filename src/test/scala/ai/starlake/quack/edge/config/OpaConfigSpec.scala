package ai.starlake.quack.edge.config

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class OpaConfigSpec extends AnyFlatSpec with Matchers:

  "OpaConfig" should "load the bundled defaults from quack-flightsql.opa" in:
    val c = ConfigSource.default.at("quack-flightsql.opa").loadOrThrow[OpaConfig]
    c shouldBe OpaConfig.default

  it should "read camelCase keys" in:
    val src = ConfigSource.fromConfig(
      ConfigFactory.parseString(
        """defaultMode = "opa", url = "http://m:8181", timeoutMs = 750, cacheTtlSec = 0"""
      )
    )
    src.loadOrThrow[OpaConfig] shouldBe OpaConfig("opa", "http://m:8181", 750, 0)

  "OpaConfig.validate" should "refuse an unknown mode, a bad URL and non-positive timeout" in:
    OpaConfig.validate(OpaConfig.default.copy(defaultMode = "x")).isLeft shouldBe true
    OpaConfig.validate(OpaConfig.default.copy(url = "opa:8181")).isLeft shouldBe true
    OpaConfig.validate(OpaConfig.default.copy(timeoutMs = 0)).isLeft shouldBe true
    OpaConfig.validate(OpaConfig.default.copy(cacheTtlSec = -1)).isLeft shouldBe true
    OpaConfig.validate(OpaConfig.default) shouldBe Right(OpaConfig.default)

  it should "refuse a URL with credentials without echoing it" in:
    val err =
      OpaConfig.validate(OpaConfig.default.copy(url = "http://u:s3cr3t@m:8181")).left.toOption
    err.isDefined shouldBe true
    err.get should not include "s3cr3t"

  it should "return the trimmed URL" in:
    OpaConfig.validate(OpaConfig.default.copy(url = "  http://m:8181 ")) shouldBe
      Right(OpaConfig.default.copy(url = "http://m:8181"))
