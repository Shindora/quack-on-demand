package ai.starlake.quack.edge.opa

import ai.starlake.acl.parser.TableAccess
import io.circe.Json

import java.io.InputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** HTTP transport to an OPA server. `post` is injectable for tests; the default is a JDK
  * `HttpClient` call bounded by `timeoutMs` (connect and request).
  */
final class OpaClient(timeoutMs: Int, post: OpaClient.Post):

  def query(url: String, token: Option[String], input: Json, accesses: Set[TableAccess]): Decision =
    post(url, input.noSpaces, token) match
      case Left(err)                                        => Decision.Error(err)
      case Right((code, body)) if code >= 200 && code < 300 => OpaDecision.parse(body, accesses)
      case Right((code, _)) => Decision.Error(s"OPA returned HTTP $code")

object OpaClient:
  type Post = (String, String, Option[String]) => Either[String, (Int, String)]

  /** Hard cap on the response body a `Post` implementation may hand back: an OPA policy misbehaving
    * (or a compromised OPA) should not be able to force unbounded heap growth on the manager.
    */
  val MaxResponseBytes: Int = 1024 * 1024

  def endpoint(base: String, policyPath: String, rule: String): String =
    s"${base.stripSuffix("/")}/v1/data/$policyPath/$rule"

  def jdkPost(timeoutMs: Int): Post =
    val client =
      HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs.toLong)).build()
    (url, body, token) =>
      try
        val b = HttpRequest
          .newBuilder(URI.create(url))
          .timeout(Duration.ofMillis(timeoutMs.toLong))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
        token.foreach(t => b.header("Authorization", s"Bearer $t"))
        val resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
        readBounded(resp.body()) match
          case Left(err)       => Left(err)
          case Right(bodyText) => Right((resp.statusCode(), bodyText))
      catch
        case e: Exception =>
          Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")

  /** Reads at most `MaxResponseBytes + 1` bytes; if that many were available, the body exceeded the
    * cap and is rejected rather than buffered in full.
    */
  private def readBounded(in: InputStream): Either[String, String] =
    try
      val bytes = in.readNBytes(MaxResponseBytes + 1)
      if bytes.length > MaxResponseBytes then Left("OPA response exceeds 1 MiB")
      else Right(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
    finally in.close()
