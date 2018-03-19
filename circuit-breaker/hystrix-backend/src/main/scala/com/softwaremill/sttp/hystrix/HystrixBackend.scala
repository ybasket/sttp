package com.softwaremill.sttp.hystrix

import com.netflix.hystrix.HystrixObservableCommand.Setter
import com.netflix.hystrix.{HystrixCommand, HystrixCommandGroupKey, HystrixCommandProperties, HystrixObservableCommand}
import com.softwaremill.sttp.{MonadAsyncError, MonadError, Request, Response, SttpBackend}
import rx.{Observable, Subscriber}

class HystrixBackend[R[_], S] private(delegate: SttpBackend[R, S])(groupKey: String, setter: HystrixCommandProperties.Setter)
  extends SttpBackend[R, S] {

  class AsyncSttpCMD[T](request: Request[T, S], delegate: SttpBackend[R, S])
    extends HystrixObservableCommand[Response[T]](
      Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey(groupKey))
        .andCommandPropertiesDefaults(setter)
    ) {

    override def construct(): Observable[Response[T]] = Observable.create(
      (t: Subscriber[_ >: Response[T]]) => {
        val x = responseMonad.flatMap(delegate.send(request)) { response =>
          t.onNext(response)
          t.onCompleted()
          responseMonad.unit(response)
        }
        responseMonad.handleError(x){ case e: Throwable =>
          t.onError(e)
          responseMonad.error(e)
        }
      }
    )
  }

  class SyncSttpCMD[T](request: Request[T, S], delegate: SttpBackend[R, S])
    extends HystrixCommand[R[Response[T]]](
      HystrixCommand.Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey(groupKey))
        .andCommandPropertiesDefaults(setter)
    ) {
    override def run(): R[Response[T]] = delegate.send(request)
  }

  override def send[T](request: Request[T, S]): R[Response[T]] = {

    responseMonad match {
      case mae: MonadAsyncError[R] => mae.async { cb =>

        new AsyncSttpCMD(request, delegate).observe().subscribe(
          new Subscriber[Response[T]]() {
            def onNext(item: Response[T]): Unit = {
              cb(Right(item))
            }

            def onError(error: Throwable): Unit = cb(Left(error))

            def onCompleted(): Unit = {}
          })
      }

      case _ => new SyncSttpCMD(request, delegate).execute()
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
  def apply[R[_], S](delegate: SttpBackend[R, S])
                    (groupKey: String, setter: HystrixCommandProperties.Setter = HystrixCommandProperties.Setter()) =
    new HystrixBackend(delegate)(groupKey, setter)
}