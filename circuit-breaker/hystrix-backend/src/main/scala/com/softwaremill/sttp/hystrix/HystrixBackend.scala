package com.softwaremill.sttp.hystrix

import com.netflix.hystrix.HystrixObservableCommand.Setter
import com.netflix.hystrix.{HystrixCommand, HystrixCommandGroupKey, HystrixCommandProperties, HystrixObservableCommand}
import com.softwaremill.sttp.{MonadAsyncError, MonadError, Request, Response, SttpBackend}
import rx.{Observable, Subscriber}

class HystrixBackend[R[_], S] private (delegate: SttpBackend[R, S])(groupKey: String,
                                                                    propertySetter: HystrixCommandProperties.Setter)
    extends SttpBackend[R, S] {

  import HystrixBackend._

  class AsyncSttpCMD[T](request: Request[T, S], delegate: SttpBackend[R, S])
      extends HystrixObservableCommand[Response[T]](
        request.tag(HystrixAsyncConfiguration).map(_.asInstanceOf[HystrixObservableCommand.Setter]) match {
          case None =>
            Setter
              .withGroupKey(HystrixCommandGroupKey.Factory.asKey(groupKey))
              .andCommandPropertiesDefaults(propertySetter)
          case Some(s) => s
        }
      ) {

    override def construct(): Observable[Response[T]] = Observable.create(
      (t: Subscriber[_ >: Response[T]]) => {
        val x = responseMonad.flatMap(delegate.send(request)) { response =>
          t.onNext(response)
          t.onCompleted()
          responseMonad.unit(response)
        }
        responseMonad.handleError(x) {
          case e: Throwable =>
            t.onError(e)
            responseMonad.error(e)
        }
      }
    )
  }

  class SyncSttpCMD[T](request: Request[T, S], delegate: SttpBackend[R, S])
      extends HystrixCommand[R[Response[T]]](
        request.tag(HystrixSyncConfiguration).map(_.asInstanceOf[HystrixCommand.Setter]) match {
          case None =>
            HystrixCommand.Setter
              .withGroupKey(HystrixCommandGroupKey.Factory.asKey(groupKey))
              .andCommandPropertiesDefaults(propertySetter)
          case Some(s) => s
        }
      ) {

    private var fallback: R[Response[T]] = responseMonad.unit(null)

    override def run(): R[Response[T]] = {
      val rr: R[Response[T]] = responseMonad.map(delegate.send(request)) {
        case errResponse @ Response(Left(error), _, _, _, _) =>
          fallback = responseMonad.unit(errResponse)
          throw new RuntimeException(error)
        case r: Response[T] => r
      }

      responseMonad.handleError(rr) {
        case t: Throwable =>
          fallback = responseMonad.error(t)
          throw new RuntimeException(t)
      }
    }

    override def getFallback: R[Response[T]] = fallback

  }

  override def send[T](request: Request[T, S]): R[Response[T]] = {

    responseMonad match {
      case mae: MonadAsyncError[R] =>
        mae.async { cb =>
          new AsyncSttpCMD(request, delegate)
            .observe()
            .subscribe(new Subscriber[Response[T]]() {
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
  def apply[R[_], S](delegate: SttpBackend[R, S])(groupKey: String,
                                                  propertySetter: HystrixCommandProperties.Setter =
                                                    HystrixCommandProperties.Setter()) =
    new HystrixBackend(delegate)(groupKey, propertySetter)

  private val HystrixAsyncConfiguration = "HystrixAsyncConfiguration"

  private val HystrixSyncConfiguration = "HystrixSyncConfiguration"

  implicit class HystrixRichRequest[T, S](request: Request[T, S]) {
    def configureAsyncHystrix(setter: HystrixObservableCommand.Setter): Request[T, S] =
      request.tag(HystrixAsyncConfiguration, setter)

    def configureSyncHystrix(setter: HystrixCommand.Setter): Request[T, S] =
      request.tag(HystrixSyncConfiguration, setter)
  }

}
