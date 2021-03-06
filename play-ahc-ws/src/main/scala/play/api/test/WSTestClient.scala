/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package play.api.test

import play.api.libs.ws.ahc.{AhcWSClient, AhcWSClientConfig, StandaloneAhcWSClient}
import play.api.libs.ws.{WSClient, WSRequest}

/**
 * A standalone test client that is useful for running standalone integration tests.
 */
trait WsTestClient {

  type Port = Int

  private val clientProducer: (Port, String) => WSClient = { (port, scheme) =>
    new WsTestClient.InternalWSClient(scheme, port)
  }

  /**
   * Run the given block of code with a client.
   *
   * The client passed to the block of code supports absolute path relative URLs passed to the url method.  If an
   * absolute path relative URL is used, the protocol is assumed to be http, the host localhost, and the port is the
   * implicit port parameter passed to this method.  This is designed to work smoothly with the Server.with* methods,
   * for example:
   *
   * {{{
   * Server.withRouter() {
   *   case GET(p"/hello/\$who") => Action(Ok("Hello " + who))
   * } { implicit port =>
   *   withClient { ws =>
   *     await(ws.url("/hello/world").get()).body must_== "Hello world"
   *   }
   * }
   * }}}
   *
   * @param block The block of code to run
   * @param port  The port
   * @return The result of the block of code
   */
  def withClient[T](block: WSClient => T)(implicit port: Int = -1, scheme: String = "http") = {
    val client = clientProducer(port, scheme)
    try {
      block(client)
    } finally {
      client.close()
    }
  }
}

object WsTestClient extends WsTestClient {

  private val singletonClient = new SingletonPlainWSClient()

  /**
   * Creates a standalone WSClient, using its own ActorSystem and Netty thread pool.
   *
   * This client has no dependencies at all on the underlying system, but is wasteful of resources.
   *
   * @param port   the port to connect to the server on.
   * @param scheme the scheme to connect on ("http" or "https")
   */
  class InternalWSClient(scheme: String, port: Port) extends WSClient {

    singletonClient.addReference(this)

    override def underlying[T] = singletonClient.underlying.asInstanceOf[T]

    override def url(url: String): WSRequest = {
      if (url.startsWith("/") && port != -1) {
        singletonClient.url(s"$scheme://localhost:$port$url")
      } else {
        singletonClient.url(url)
      }
    }

    /** Closes this client, and releases underlying resources. */
    override def close(): Unit = {
      singletonClient.removeReference(this)
    }
  }

  /**
   * A singleton ws client that keeps an ActorSystem / AHC client around only when
   * it is needed.
   */
  private class SingletonPlainWSClient extends WSClient {

    import java.util.concurrent._
    import java.util.concurrent.atomic._

    import akka.actor.{ActorSystem, Cancellable, Terminated}
    import akka.stream.ActorMaterializer

    import scala.concurrent.Future
    import scala.concurrent.duration._

    private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

    private val ref = new AtomicReference[WSClient]()

    private val count = new AtomicInteger(1)

    private val references = new ConcurrentLinkedQueue[WSClient]()

    private val idleDuration = 5.seconds

    private var idleCheckTask: Option[Cancellable] = None

    def removeReference(client: InternalWSClient) = {
      references.remove(client)
    }

    def addReference(client: InternalWSClient) = {
      references.add(client)
    }

    private def closeIdleResources(client: WSClient, system: ActorSystem): Future[Terminated] = {
      ref.set(null)
      client.close()
      system.terminate()
    }

    private def instance: WSClient = {
      val client = ref.get()
      if (client == null) {
        val result = createNewClient()
        ref.compareAndSet(null, result)
        ref.get()
      } else {
        client
      }
    }

    private def createNewClient(): WSClient = {
      val name = "ws-test-client-" + count.getAndIncrement()
      logger.warn(s"createNewClient: name = $name")

      val system = ActorSystem(name)
      implicit val materializer = ActorMaterializer(namePrefix = Some(name))(system)
      // Don't retry for tests

      val config = AhcWSClientConfig(maxRequestRetry = 0)
      val client = new AhcWSClient(StandaloneAhcWSClient(config))
      scheduleIdleCheck(client, system)
      client
    }

    private def scheduleIdleCheck(client: WSClient, system: ActorSystem) = {
      val scheduler = system.scheduler
      idleCheckTask match {
        case Some(cancellable) =>
          // Something else got here first...
          logger.error(s"scheduleIdleCheck: looks like a race condition of WsTestClient...")
          closeIdleResources(client, system)

        case None =>
          //
          idleCheckTask = Option(scheduler.schedule(initialDelay = idleDuration, interval = idleDuration) {
            if (references.size() == 0) {
              logger.debug(s"check: no references found on client $client, system $system")
              idleCheckTask.map(_.cancel())
              idleCheckTask = None
              closeIdleResources(client, system)
            } else {
              logger.debug(s"check: client references = ${references.toArray.toSeq}")
            }
          }(system.dispatcher))
      }
    }

    /**
     * The underlying implementation of the client, if any.  You must cast explicitly to the type you want.
     *
     * @tparam T the type you are expecting (i.e. isInstanceOf)
     * @return the backing class.
     */
    override def underlying[T]: T = instance.underlying

    /**
     * Generates a request holder which can be used to build requests.
     *
     * @param url The base URL to make HTTP requests to.
     * @return a WSRequestHolder
     */
    override def url(url: String): WSRequest = instance.url(url)

    override def close(): Unit = {}
  }

}
