package com.softwaremill.sttp.prometheus

import com.netflix.hystrix.{HystrixCommandKey, HystrixCommandMetrics}
import com.softwaremill.sttp.akkahttp.AkkaHttpBackend
import com.softwaremill.sttp.hystrix.HystrixBackend
import com.softwaremill.sttp.testing.SttpBackendStub
import com.softwaremill.sttp.{HttpURLConnectionBackend, Id, sttp, _}
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers, OptionValues}

import scala.concurrent.Future

class HystrixBackendTest extends FlatSpec with Matchers with BeforeAndAfter with Eventually with OptionValues
  with ScalaFutures with IntegrationPatience{

  before {
  }

  it should "use default hystrix commands" in {
    // given
    val backend = HystrixBackend(AkkaHttpBackend())
    val requestsNumber = 10

    // when
    (0 until requestsNumber).map(_ => backend.send(sttp.get(uri"http://httpbin.org/get")).futureValue).foreach(println)

    // then
    val metrics = HystrixCommandMetrics.getInstance(HystrixCommandKey.Factory.asKey("SttpCMD"))
    println(metrics.getExecutionTimeMean)
    println(metrics.getExecutionTimePercentile(10))
    println(metrics.getHealthCounts)
    println(metrics.getCommandGroup)
  }

}
