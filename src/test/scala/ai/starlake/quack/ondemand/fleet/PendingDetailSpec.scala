package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.ondemand.state.FleetServerRow
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class PendingDetailSpec extends AnyFlatSpec with Matchers:

  private def row(
      name: String,
      approved: Boolean = true,
      drained: Boolean = false,
      assigned: Option[String] = None,
      silent: Long = 0,
      mem: Option[Long] = Some(4L << 30)
  ) = FleetServerRow(
    name = name,
    advertiseHost = "10.0.0.1",
    nodePort = 21900,
    joinedAt = Instant.EPOCH,
    unschedulable = drained,
    assignedNodeId = assigned,
    assignment = None,
    assignmentEpoch = 0,
    claimedAt = None,
    lastHeartbeatAt = Instant.EPOCH,
    silentSeconds = silent,
    claimAgeSeconds = None,
    qodVersion = None,
    os = None,
    duckdbVersion = None,
    cpus = None,
    memoryBytes = mem,
    nodeState = "none",
    nodeError = None,
    nodePid = None,
    nodeStartedAt = None,
    approved = approved,
    approvedAt = None,
    approvedBy = None,
    sourceAddr = None,
    approvedSource = None
  )

  private val live: FleetServerRow => ServerLiveness =
    r => FleetLiveness.classify(r.silentSeconds, 30, 600)

  "describe" should "compare the memory a slot needs with the largest free server" in {
    PendingDetail.describe(
      "none_fits",
      List(row("a"), row("b", mem = Some(2L << 30))),
      live,
      Some(8L << 30)
    ) shouldBe
      "needs 8.0 GiB; the largest free server (a) reports 4.0 GiB: add a server with more " +
      "memory or lower the pool's memory request"
  }

  it should "count servers by state and name the next command for each" in {
    val rows = List(
      row("a", drained = true),
      row("b", approved = false),
      row("c", assigned = Some("n1"))
    )
    PendingDetail.describe("none_free", rows, live, None) shouldBe
      "3 servers: 1 pending approval (b), 1 drained (a), 1 busy (c): qod fleet approve b, " +
      "or qod fleet undrain a, or join another server"
  }

  it should "say so when no server has joined" in {
    PendingDetail.describe("none_free", Nil, live, None) shouldBe
      "no server has joined the fleet: run qod fleet join on a server"
  }

  it should "point at the join process of an unreachable server" in {
    PendingDetail.describe("none_free", List(row("d", silent = 100)), live, None) shouldBe
      "1 server: 1 unreachable (d): check qod fleet join on d, or join another server"
  }

  it should "cap the names listed per state" in {
    val rows = (1 to 7).toList.map(i => row(s"s$i", assigned = Some(s"n$i")))
    PendingDetail.describe("none_free", rows, live, None) shouldBe
      "7 servers: 7 busy (s1, s2, s3, s4, s5, +2 more): join another server"
  }
