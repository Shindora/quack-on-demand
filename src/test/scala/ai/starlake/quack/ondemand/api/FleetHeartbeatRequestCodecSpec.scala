package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.api.Dtos.given
import io.circe.parser.decode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FleetHeartbeatRequestCodecSpec extends AnyFlatSpec with Matchers:

  private def body(versionField: String) =
    s"""{"name":"srv-1","advertiseHost":"10.0.0.1","nodePort":21900,$versionField"os":"linux",
       |"duckdbVersion":"1.5.5","cpus":8,"memoryBytes":1024,
       |"node":{"assignmentEpoch":0,"state":"none"}}""".stripMargin

  "FleetHeartbeatRequest" should "read qodVersion" in {
    decode[FleetHeartbeatRequest](body(""""qodVersion":"0.9.8",""")).map(_.qodVersion) shouldBe
      Right(Some("0.9.8"))
  }

  it should "accept a body from a server older than the rename, recording no version" in {
    // Released 0.9.7 servers send `agentVersion`; the unknown field is ignored, not rejected.
    decode[FleetHeartbeatRequest](body(""""agentVersion":"0.9.7",""")).map(_.qodVersion) shouldBe
      Right(None)
  }

  "FleetNodeReportDto" should "read action, and default it to None when absent" in {
    decode[FleetNodeReportDto](
      """{"assignmentEpoch":1,"nodeId":"n1","state":"failed","error":"boom","action":"fix it"}"""
    ).map(_.action) shouldBe Right(Some("fix it"))
    decode[FleetNodeReportDto]("""{"assignmentEpoch":0,"state":"none"}""").map(_.action) shouldBe
      Right(None)
  }
