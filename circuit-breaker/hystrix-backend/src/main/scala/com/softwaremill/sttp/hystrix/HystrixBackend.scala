package com.softwaremill.sttp.hystrix

import com.netflix.hystrix.HystrixObservableCommand.Setter
import com.netflix.hystrix.{HystrixCommand, HystrixCommandGroupKey, HystrixCommandProperties, HystrixObservableCommand}
import com.softwaremill.sttp.{MonadAsyncError, MonadError, Request, Response, SttpBackend}
import rx.functions.Action
import rx.{Observable, Subscriber}
import rx.observables.AsyncOnSubscribe

class HystrixBackend[R[_], S] private(delegate: SttpBackend[R, S]) extends SttpBackend[R, S] {

  class SttpCMD[T](request: Request[T, S], delegate: SttpBackend[R, S])
    extends HystrixObservableCommand[Response[T]](
      Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("SttpCommand"))
        .andCommandPropertiesDefaults(HystrixCommandProperties.Setter().withMetricsHealthSnapshotIntervalInMilliseconds(10))
    ) {

    override def construct(): Observable[Response[T]] = Observable.create(
      (t: Subscriber[_ >: Response[T]]) => {
        val x = responseMonad.flatMap(delegate.send(request)) { response =>
          println(s"GOT RESPONSEEEE!!!! $response")
          t.onNext(response)
          t.onCompleted()
          responseMonad.unit(response)
        }
        responseMonad.handleError(x){ case e: Throwable =>
          println(s"GOT ERRRORRRR!!! $e")
          t.onError(e)
          responseMonad.error(e)
        }
      }
    )
  }

  override def send[T](request: Request[T, S]): R[Response[T]] = {

    responseMonad.asInstanceOf[MonadAsyncError[R]].async { cb =>

      new SttpCMD(request, delegate).observe().subscribe(
        new Subscriber[Response[T]]() {
          def onNext(item: Response[T]): Unit = {
            cb(Right(item))
          }

          def onError(error: Throwable): Unit = cb(Left(error))

          def onCompleted(): Unit = {
            System.out.println("Sequence complete.")
          }
        })
    }
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