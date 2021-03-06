package play.api.libs.ws.ahc

import javax.inject.{Inject, Provider, Singleton}

import akka.stream.Materializer
import org.asynchttpclient.{AsyncHttpClient, DefaultAsyncHttpClient}
import play.api.inject.ApplicationLifecycle
import play.api.libs.ws.{WSClient, WSClientConfig, WSConfigParser}
import play.api.{Configuration, Environment}

@Singleton
class AsyncHttpClientProvider @Inject()(configuration: Configuration,
                                        environment: Environment,
                                        applicationLifecycle: ApplicationLifecycle) extends Provider[AsyncHttpClient] {
  val wsClientConfig: WSClientConfig = new WSConfigParser(configuration, environment).parse()
  val ahcWsClientConfig: AhcWSClientConfig =
    new AhcWSClientConfigParser(wsClientConfig, configuration, environment).parse()

  val asyncHttpClientConfig = new AhcConfigBuilder(ahcWsClientConfig).build()

  lazy val get = new DefaultAsyncHttpClient(asyncHttpClientConfig)
}

@Singleton
class PlainAhcWSClientProvider @Inject()(asyncHttpClient: AsyncHttpClient)(implicit materializer: Materializer)
  extends Provider[StandaloneAhcWSClient] {

  lazy val get: StandaloneAhcWSClient = new StandaloneAhcWSClient(asyncHttpClient)
}

@Singleton
class WSClientProvider @Inject()(plainAhcWSClient: StandaloneAhcWSClient)
  extends Provider[WSClient] {

  lazy val get: WSClient = new AhcWSClient(plainAhcWSClient)
}
