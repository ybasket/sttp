package sttp.client.brave

import brave.http.HttpTracing
import brave.internal.HexCodec
import brave.test.http.ITHttpClient
import okhttp3.mockwebserver.MockResponse
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import sttp.client.{SttpBackend, _}
import zipkin2.Span

class BraveBackendTest extends FlatSpec with Matchers with BeforeAndAfter {

  // test proxy - contains the brave instrumentation tests
  private var t: ITHttpClient[SttpBackend[Identity, Nothing, NothingT]] = null

  // we need to extract these protected ITHttpClient members to use in the custom test
  private var _backend: SttpBackend[Identity, Nothing, NothingT] = null
  private var _httpTracing: HttpTracing = null
  private var _takeSpan: () => Span = null

  def newT(): Unit = {
    t = new ITHttpClient[SttpBackend[Identity, Nothing, NothingT]]() {
      override def post(
          client: SttpBackend[Identity, Nothing, NothingT],
          pathIncludingQuery: String,
          body: String
      ): Unit = {
        client.send(basicRequest.post(uri"${url(pathIncludingQuery)}").body(body))
      }

      override def get(client: SttpBackend[Identity, Nothing, NothingT], pathIncludingQuery: String): Unit = {
        client.send(basicRequest.get(uri"${url(pathIncludingQuery)}"))
      }

      override def closeClient(client: SttpBackend[Identity, Nothing, NothingT]): Unit =
        client.close()

      override def newClient(port: Int): SttpBackend[Identity, Nothing, NothingT] = {
        _backend = BraveBackend[Identity, Nothing](HttpURLConnectionBackend(), httpTracing)
        _httpTracing = httpTracing
        _takeSpan = () => takeSpan()

        _backend
      }
    }
  }

  before {
    newT()
    t.setup()
  }

  after {
    t.close()
    t.server.shutdown()
  }

  it should "propagatesSpan" in {
    t.propagatesSpan()
  }

  it should "makesChildOfCurrentSpan" in {
    t.makesChildOfCurrentSpan()
  }

  it should "propagatesExtra_newTrace" in {
    t.propagatesExtra_newTrace()
  }

  it should "propagatesExtra_unsampledTrace" in {
    t.propagatesExtra_unsampledTrace()
  }

  it should "propagates_sampledFalse" in {
    t.propagates_sampledFalse()
  }

  it should "customSampler" in {
    t.customSampler()
  }

  it should "reportsClientKindToZipkin" in {
    t.reportsClientKindToZipkin()
  }

  it should "defaultSpanNameIsMethodName" in {
    t.defaultSpanNameIsMethodName()
  }

  it should "supportsPortableCustomization" in {
    t.supportsPortableCustomization()
  }

  it should "addsStatusCodeWhenNotOk" in {
    t.addsStatusCodeWhenNotOk()
  }

  it should "redirect" in {
    t.redirect()
  }

  it should "post" in {
    t.post()
  }

// these tests take a very long time to complete, but pass last time I checked

//  it should "reportsSpanOnTransportException" in {
//    t.reportsSpanOnTransportException()
//  }

//  it should "addsErrorTagOnTransportException" in {
//    t.addsErrorTagOnTransportException()
//  }

  it should "httpPathTagExcludesQueryParams" in {
    t.httpPathTagExcludesQueryParams()
  }

  it should "use the tracing context from tags if available" in {
    val tracer = _httpTracing.tracing.tracer
    t.server.enqueue(new MockResponse)

    val parent = tracer.newTrace.name("test").start
    try {
      import sttp.client.brave.BraveBackend._
      _backend.send(
        basicRequest
          .get(uri"http://127.0.0.1:${t.server.getPort}/foo")
          .tagWithTraceContext(parent.context())
      )
    } finally parent.finish()

    val req = t.server.takeRequest
    req.getHeader("x-b3-traceId") should be(parent.context.traceIdString)
    req.getHeader("x-b3-parentspanid") should be(HexCodec.toLowerHex(parent.context.spanId))

    Set(_takeSpan(), _takeSpan()).map(_.kind) should be(Set(null, Span.Kind.CLIENT))
  }
}
