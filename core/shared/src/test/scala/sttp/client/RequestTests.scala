package sttp.client

import org.scalatest.{FlatSpec, Matchers}
import sttp.model.HeaderNames

class RequestTests extends FlatSpec with Matchers {

  "content length" should "be automatically set for a string body" in {
    basicRequest
      .body("test")
      .headers
      .find(_.name.equalsIgnoreCase(HeaderNames.ContentLength))
      .map(_.value) should be(Some("4"))
  }

  it should "be automatically set to the number of utf-8 bytes in a string" in {
    basicRequest
      .body("ąęć")
      .headers
      .find(_.name.equalsIgnoreCase(HeaderNames.ContentLength))
      .map(_.value) should be(Some("6"))
  }

  it should "not override an already specified content length" in {
    basicRequest
      .contentLength(10)
      .body("a")
      .headers
      .find(_.name.equalsIgnoreCase(HeaderNames.ContentLength))
      .map(_.value) should be(Some("10"))
  }

  "request timeout" should "use default if not overridden" in {
    basicRequest.options.readTimeout should be(DefaultReadTimeout)
  }
}
