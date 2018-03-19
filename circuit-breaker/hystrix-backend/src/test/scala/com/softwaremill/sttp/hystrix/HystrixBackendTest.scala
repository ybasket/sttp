package com.softwaremill.sttp.prometheus

import com.netflix.hystrix.{HystrixCommandKey, HystrixCommandMetrics}
import com.softwaremill.sttp.hystrix.HystrixBackend
import com.softwaremill.sttp.testing.SttpBackendStub
import com.softwaremill.sttp.{HttpURLConnectionBackend, Id, sttp, _}
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers, OptionValues}

class HystrixBackendTest extends FlatSpec with Matchers with BeforeAndAfter with Eventually with OptionValues {

  before {
  }

  it should "use default hystrix commands" in {
    // given
    val backendStub = SttpBackendStub(HttpURLConnectionBackend()).whenAnyRequest.thenRespondOk()
    val backend = HystrixBackend[Id, Nothing](backendStub)
    val requestsNumber = 10

    // when
    (0 until requestsNumber).map(_ => backend.send(sttp.get(uri"http://127.0.0.1/foo"))).foreach(println)

    // then
    val metrics = HystrixCommandMetrics.getInstance(HystrixCommandKey.Factory.asKey("SttpCMD"))
    println(metrics.getExecutionTimeMean)
    println(metrics.getExecutionTimePercentile(10))
    println(metrics.getHealthCounts)
    println(metrics.getCommandGroup)
  }

}
