package ai.starlake.quack.ondemand.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit tests for [[FederatedSourceStore.withTimeouts]] - no database required. Pins that a
  * fresh-per-call JDBC connection (see FederatedSourceStore.withConn) can never hang a caller
  * indefinitely: both connectTimeout and socketTimeout end up on the URL, and a caller-supplied
  * value for either is never overridden.
  */
class FederatedSourceStoreUrlSpec extends AnyFlatSpec with Matchers:

  "withTimeouts" should "append both timeouts to a bare URL" in {
    val url = FederatedSourceStore.withTimeouts("jdbc:postgresql://localhost:5432/qod")
    url shouldBe "jdbc:postgresql://localhost:5432/qod?connectTimeout=10&socketTimeout=30"
  }

  it should "append both timeouts to a URL that already carries an unrelated query param" in {
    val url =
      FederatedSourceStore.withTimeouts("jdbc:postgresql://localhost:5432/qod?ssl=true")
    url shouldBe "jdbc:postgresql://localhost:5432/qod?ssl=true&connectTimeout=10&socketTimeout=30"
  }

  it should "keep a caller-supplied socketTimeout and only add connectTimeout" in {
    val url = FederatedSourceStore.withTimeouts(
      "jdbc:postgresql://localhost:5432/qod?socketTimeout=5"
    )
    url shouldBe "jdbc:postgresql://localhost:5432/qod?socketTimeout=5&connectTimeout=10"
  }

  it should "leave a URL that already carries both timeouts unchanged" in {
    val original =
      "jdbc:postgresql://localhost:5432/qod?connectTimeout=7&socketTimeout=20"
    FederatedSourceStore.withTimeouts(original) shouldBe original
  }
