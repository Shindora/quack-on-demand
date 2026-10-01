package ai.starlake.quack.edge.opa

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpaDecisionCacheSpec extends AnyFlatSpec with Matchers:

  private def key(roles: List[String] = List("r"), acc: List[String] = List("a:read")) =
    OpaCacheKey("t", "s", "u", roles, Nil, Nil, "t/db/p", "statement", acc)

  "OpaDecisionCache" should "hit on the same key and miss on any key difference" in:
    val c                   = new OpaDecisionCache(ttlSec = 60)
    var calls               = 0
    def run(k: OpaCacheKey) = c.getOrCompute(k) { calls += 1; Decision.Allow(None) }
    run(key())._2 shouldBe false
    run(key())._2 shouldBe true
    run(key(roles = List("r2")))._2 shouldBe false
    run(key(acc = List("b:write")))._2 shouldBe false
    calls shouldBe 3

  it should "never cache Error" in:
    val c = new OpaDecisionCache(ttlSec = 60)
    c.getOrCompute(key())(Decision.Error("down"))._2 shouldBe false
    c.getOrCompute(key())(Decision.Allow(None)) shouldBe (Decision.Allow(None), false)

  it should "bypass entirely with ttl 0" in:
    val c = new OpaDecisionCache(ttlSec = 0)
    c.getOrCompute(key())(Decision.Allow(None))._2 shouldBe false
    c.getOrCompute(key())(Decision.Allow(None))._2 shouldBe false
