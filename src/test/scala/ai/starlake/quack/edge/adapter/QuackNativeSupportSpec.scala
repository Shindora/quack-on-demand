package ai.starlake.quack.edge.adapter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the native-client platform fallback: on a platform with no bundled libquackwire (Windows on
  * ARM64 - quackwire.dll is built x86_64-only), QOD_NATIVE_CLIENT=true must degrade to the embedded
  * HTTP client instead of crashing at JNI load.
  */
class QuackNativeSupportSpec extends AnyFlatSpec with Matchers:

  "QuackNativeSupport.available" should "see the bundled native for this platform" in {
    // The four Unix platform binaries are vendored under libquackwire/binaries/
    // and bundled into resources at build time; dev machines and CI are all
    // Unix, so the current platform's native must be present.
    QuackNativeSupport.availableForThisPlatform shouldBe true
  }

  it should "not see a native for a platform with no bundled binary" in {
    QuackNativeSupport.available("windows-aarch64", "quackwire.dll") shouldBe false
  }

  "QuackNativeSupport.effectiveNativeClient" should "pass the configured value through when the native is bundled" in {
    QuackNativeSupport.effectiveNativeClient(configured = true, nativeBundled = true) shouldBe true
    QuackNativeSupport.effectiveNativeClient(
      configured = false,
      nativeBundled = true
    ) shouldBe false
  }

  it should "force false when the native is missing, regardless of configuration" in {
    QuackNativeSupport.effectiveNativeClient(
      configured = true,
      nativeBundled = false
    ) shouldBe false
    QuackNativeSupport.effectiveNativeClient(
      configured = false,
      nativeBundled = false
    ) shouldBe false
  }

  it should "not attempt a load when nativeBundled is false" in {
    var invoked = false
    val result  = QuackNativeSupport.effectiveNativeClient(
      configured = true,
      nativeBundled = false,
      tryLoad = () => invoked = true
    )
    result shouldBe false
    invoked shouldBe false
  }

  it should "not attempt a load when configured is false" in {
    var invoked = false
    val result  = QuackNativeSupport.effectiveNativeClient(
      configured = false,
      nativeBundled = true,
      tryLoad = () => invoked = true
    )
    result shouldBe false
    invoked shouldBe false
  }

  it should "return true when configured, bundled, and the load succeeds" in {
    var invoked = false
    val result  = QuackNativeSupport.effectiveNativeClient(
      configured = true,
      nativeBundled = true,
      tryLoad = () => invoked = true
    )
    result shouldBe true
    invoked shouldBe true
  }

  it should "degrade to false when configured and bundled but the load fails" in {
    val brokenLoad = () =>
      throw new ExceptionInInitializerError(
        new UnsatisfiedLinkError("Can't find dependent libraries")
      )
    val result = QuackNativeSupport.effectiveNativeClient(
      configured = true,
      nativeBundled = true,
      tryLoad = brokenLoad
    )
    result shouldBe false
  }
