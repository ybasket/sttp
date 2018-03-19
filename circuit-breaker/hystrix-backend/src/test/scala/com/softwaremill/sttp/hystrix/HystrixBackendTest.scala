package com.softwaremill.sttp.prometheus

import com.netflix.hystrix.{HystrixCommandKey, HystrixCommandMetrics, HystrixCommandProperties}
import com.softwaremill.sttp.hystrix.HystrixBackend
import com.softwaremill.sttp.testing.SttpBackendStub
import com.softwaremill.sttp.{sttp, _}
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers, OptionValues}

import scala.concurrent.Future

class HystrixBackendTest extends FlatSpec with Matchers with BeforeAndAfter with Eventually with OptionValues
  with ScalaFutures with IntegrationPatience{

  it should "use default hystrix commands on async backend" in {
    // given
    val backendStub = SttpBackendStub.asynchronousFuture.whenAnyRequest.thenRespondOk()

    val backend = HystrixBackend[Future, Nothing](backendStub)("TestAsyncCMD", HystrixCommandProperties.Setter().withMetricsHealthSnapshotIntervalInMilliseconds(10))
    val requestsNumber = 10

    // when
    (0 until requestsNumber).map(_ => backend.send(sttp.get(uri"http://localhost:8080/get")).futureValue)

    // then
    val metrics = HystrixCommandMetrics.getInstance(HystrixCommandKey.Factory.asKey("AsyncSttpCMD"))

    Thread.sleep(100) // wait for the health metrics

    metrics.getHealthCounts.getErrorPercentage shouldBe 0
    metrics.getHealthCounts.getTotalRequests shouldBe 10
  }

  it should "use default hystrix commands on sync backend" in {
    // given
    val backendStub = SttpBackendStub.synchronous.whenAnyRequest.thenRespondOk()

    val backend = HystrixBackend[Id, Nothing](backendStub)("TestSyncCMD", HystrixCommandProperties.Setter().withMetricsHealthSnapshotIntervalInMilliseconds(10))
    val requestsNumber = 10

    // when
    (0 until requestsNumber).map(_ => backend.send(sttp.get(uri"http://localhost:8080/get")))

    // then
    val metrics = HystrixCommandMetrics.getInstance(HystrixCommandKey.Factory.asKey("SyncSttpCMD"))

    Thread.sleep(100) // wait for the health metrics

    metrics.getHealthCounts.getErrorPercentage shouldBe 0
    metrics.getHealthCounts.getTotalRequests shouldBe 10
  }

}
