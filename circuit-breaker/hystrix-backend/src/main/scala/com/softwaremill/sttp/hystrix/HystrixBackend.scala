package com.softwaremill.sttp.hystrix

import com.netflix.hystrix.HystrixCommand.Setter
import com.netflix.hystrix.{HystrixCommand, HystrixCommandGroupKey, HystrixCommandProperties}
import com.softwaremill.sttp.{MonadError, Request, Response, SttpBackend}

class HystrixBackend[R[_], S] private (delegate: SttpBackend[R, S]) extends SttpBackend[R, S] {

  class SttpCMD[T](request: Request[T, S], delegate: SttpBackend[R, S])
    extends HystrixCommand[R[Response[T]]](
      Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("SttpCommand"))
        .andCommandPropertiesDefaults(HystrixCommandProperties.Setter().withMetricsHealthSnapshotIntervalInMilliseconds(10))
    ) {

    override def run(): R[Response[T]] = delegate.send(request)
  }

  override def send[T](request: Request[T, S]): R[Response[T]] = {
    new SttpCMD(request, delegate).execute()
  }

  override def close(): Unit = delegate.close()

  /**
    * The monad in which the responses are wrapped. Allows writing wrapper
    * backends, which map/flatMap over the return value of [[send]].
    */
  override def responseMonad: MonadError[R] = delegate.responseMonad
}

object HystrixBackend {
  def apply[R[_], S](delegate: SttpBackend[R, S]) = new HystrixBackend(delegate)
}