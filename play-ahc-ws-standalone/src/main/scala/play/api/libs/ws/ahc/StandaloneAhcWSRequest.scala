package play.api.libs.ws.ahc

import java.io.{File, UnsupportedEncodingException}
import java.net.URI
import java.nio.charset.{Charset, StandardCharsets}

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import io.netty.handler.codec.http.HttpHeaders
import org.asynchttpclient.Realm.AuthScheme
import org.asynchttpclient.cookie.{Cookie => AHCCookie}
import org.asynchttpclient.proxy.{ProxyServer => AHCProxyServer}
import org.asynchttpclient.util.HttpUtils
import org.asynchttpclient.{Realm, Response => AHCResponse, _}
import play.api.libs.ws._

import scala.collection.JavaConverters._
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}


case object StandaloneAhcWSRequest {
  private[libs] def ahcHeadersToMap(headers: HttpHeaders): TreeMap[String, Seq[String]] = {
    val mutableMap = scala.collection.mutable.HashMap[String, Seq[String]]()
    headers.names().asScala.foreach { name =>
      mutableMap.put(name, headers.getAll(name).asScala)
    }
    TreeMap[String, Seq[String]]()(CaseInsensitiveOrdered) ++ mutableMap
  }

  private[libs] def execute(request: Request, client: StandaloneAhcWSClient): Future[StandaloneAhcWSResponse] = {
    import org.asynchttpclient.AsyncCompletionHandler
    val result = Promise[StandaloneAhcWSResponse]()

    client.executeRequest(request, new AsyncCompletionHandler[AHCResponse]() {
      override def onCompleted(response: AHCResponse) = {
        result.success(StandaloneAhcWSResponse(response))
        response
      }

      override def onThrowable(t: Throwable) = {
        result.failure(t)
      }
    })
    result.future
  }
}

/**
 * A Ahc WS Request.
 */
case class StandaloneAhcWSRequest(client: StandaloneAhcWSClient,
                                  url: String,
                                  method: String,
                                  body: WSBody,
                                  headers: Map[String, Seq[String]],
                                  queryString: Map[String, Seq[String]],
                                  calc: Option[WSSignatureCalculator],
                                  auth: Option[(String, String, WSAuthScheme)],
                                  followRedirects: Option[Boolean],
                                  requestTimeout: Option[Int],
                                  virtualHost: Option[String],
                                  proxyServer: Option[WSProxyServer],
                                  disableUrlEncoding: Option[Boolean])(implicit materializer: Materializer) extends StandaloneWSRequest {
  override type Self = StandaloneWSRequest
  override type Response = StandaloneWSResponse

  override lazy val uri: URI = {
    val enc = (p: String) => java.net.URLEncoder.encode(p, "utf-8")
    new java.net.URI(if (queryString.isEmpty) url else {
      val qs = (for {
        (n, vs) <- queryString
        v <- vs
      } yield s"${enc(n)}=${enc(v)}").mkString("&")
      s"$url?$qs"
    })
  }

  def sign(calc: WSSignatureCalculator): Self = copy(calc = Some(calc))

  def withAuth(username: String, password: String, scheme: WSAuthScheme): Self =
    copy(auth = Some((username, password, scheme)))

  def withHeaders(hdrs: (String, String)*): Self = {
    val headers = hdrs.foldLeft(this.headers)((m, hdr) =>
      if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
      else m + (hdr._1 -> Seq(hdr._2))
    )
    copy(headers = headers)
  }

  def withQueryString(parameters: (String, String)*): Self =
    copy(queryString = parameters.foldLeft(this.queryString) {
      case (m, (k, v)) => m + (k -> (v +: m.getOrElse(k, Nil)))
    })

  def withFollowRedirects(follow: Boolean): Self = copy(followRedirects = Some(follow))

  def withRequestTimeout(timeout: Duration): Self = {
    timeout match {
      case Duration.Inf =>
        copy(requestTimeout = Some(-1))
      case d =>
        val millis = d.toMillis
        require(millis >= 0 && millis <= Int.MaxValue, s"Request timeout must be between 0 and ${Int.MaxValue} milliseconds")
        copy(requestTimeout = Some(millis.toInt))
    }
  }


  /**
   * performs a get
   */
  def get(): Future[Response] = withMethod("GET").execute()

  /**
   * Perform a PATCH on the request asynchronously.
   * Request body won't be chunked
   */
  def patch(body: File): Future[Response] = withMethod("PATCH").withBody(FileBody(body)).execute()

  /**
   * Perform a POST on the request asynchronously.
   * Request body won't be chunked
   */
  def post(body: File): Future[Response] = withMethod("POST").withBody(FileBody(body)).execute()

  /**
   * Perform a PUT on the request asynchronously.
   * Request body won't be chunked
   */
  def put(body: File): Future[Response] = withMethod("PUT").withBody(FileBody(body)).execute()

  /**
   * Perform a DELETE on the request asynchronously.
   */
  def delete(): Future[Response] = withMethod("DELETE").execute()

  /**
   * Perform a HEAD on the request asynchronously.
   */
  def head(): Future[Response] = withMethod("HEAD").execute()

  /**
   * Perform a OPTIONS on the request asynchronously.
   */
  def options(): Future[Response] = withMethod("OPTIONS").execute()

  def execute(method: String): Future[Response] = withMethod(method).execute()

  def withVirtualHost(vh: String): Self = copy(virtualHost = Some(vh))

  def withProxyServer(proxyServer: WSProxyServer): Self = copy(proxyServer = Some(proxyServer))

  def withBody(body: WSBody): Self = copy(body = body)

  def withMethod(method: String): Self = copy(method = method)

  def execute(): Future[Response] = {
    StandaloneAhcWSRequest.execute(this.buildRequest(), client)
  }

  def stream(): Future[StreamedResponse] = Streamed.execute(client.underlying, buildRequest())

  /**
   * Returns the current headers of the request, using the request builder.  This may be signed,
   * so may return extra headers that were not directly input.
   */
  def requestHeaders: Map[String, Seq[String]] = StandaloneAhcWSRequest.ahcHeadersToMap(buildRequest().getHeaders)

  /**
   * Returns the HTTP header given by name, using the request builder.  This may be signed,
   * so may return extra headers that were not directly input.
   */
  def requestHeader(name: String): Option[String] = requestHeaders.get(name).flatMap(_.headOption)

  /**
   * Returns the current query string parameters, using the request builder.  This may be signed,
   * so may not return the same parameters that were input.
   */
  def requestQueryParams: Map[String, Seq[String]] = {
    val params: java.util.List[Param] = buildRequest().getQueryParams
    params.asScala.toSeq.groupBy(_.getName).mapValues(_.map(_.getValue))
  }

  /**
   * Returns the current URL, using the request builder.  This may be signed by OAuth, as opposed
   * to request.url.
   */
  def requestUrl: String = buildRequest().getUrl

  /**
   * Returns the body as an array of bytes.
   */
  def getBody: Option[ByteString] = {
    body match {
      case InMemoryBody(bytes) => Some(bytes)
      case _ => None
    }
  }

  private[libs] def authScheme(scheme: WSAuthScheme): Realm.AuthScheme = scheme match {
    case WSAuthScheme.DIGEST => Realm.AuthScheme.DIGEST
    case WSAuthScheme.BASIC => Realm.AuthScheme.BASIC
    case WSAuthScheme.NTLM => Realm.AuthScheme.NTLM
    case WSAuthScheme.SPNEGO => Realm.AuthScheme.SPNEGO
    case WSAuthScheme.KERBEROS => Realm.AuthScheme.KERBEROS
    case _ => throw new RuntimeException("Unknown scheme " + scheme)
  }

  /**
   * Add http auth headers. Defaults to HTTP Basic.
   */
  private[libs] def auth(username: String, password: String, scheme: Realm.AuthScheme = Realm.AuthScheme.BASIC): Realm = {
    val usePreemptiveAuth = scheme match {
      case AuthScheme.DIGEST => false
      case _ => true
    }

    new Realm.Builder(username, password)
      .setScheme(scheme)
      .setUsePreemptiveAuth(usePreemptiveAuth)
      .build()
  }

  def contentType: Option[String] = this.headers.get(HttpHeaders.Names.CONTENT_TYPE).map(_.head)

  /**
   * Creates and returns an AHC request, running all operations on it.
   */
  def buildRequest(): org.asynchttpclient.Request = {
    // The builder has a bunch of mutable state and is VERY fiddly, so
    // should not be exposed to the outside world.

    val builder = disableUrlEncoding.map { disableEncodingFlag =>
      new RequestBuilder(method, disableEncodingFlag)
    }.getOrElse {
      new RequestBuilder(method)
    }

    // Set the URL.
    builder.setUrl(url)

    // auth
    auth.foreach { data =>
      val realm = auth(data._1, data._2, authScheme(data._3))
      builder.setRealm(realm)
    }

    // queries
    for {
      (key, values) <- queryString
      value <- values
    } builder.addQueryParam(key, value)

    // Configuration settings on the builder, if applicable
    virtualHost.foreach(builder.setVirtualHost)
    followRedirects.foreach(builder.setFollowRedirect)
    proxyServer.foreach(p => builder.setProxyServer(createProxy(p)))
    requestTimeout.foreach(builder.setRequestTimeout)

    val (builderWithBody, updatedHeaders) = body match {
      case EmptyBody => (builder, this.headers)
      case FileBody(file) =>
        import org.asynchttpclient.request.body.generator.FileBodyGenerator
        val bodyGenerator = new FileBodyGenerator(file)
        builder.setBody(bodyGenerator)
        (builder, this.headers)
      case InMemoryBody(bytes) =>
        val ct: String = contentType.getOrElse("text/plain")

        val h = try {
          // Only parse out the form body if we are doing the signature calculation.
          if (ct.contains(HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED) && calc.isDefined) {
            // If we are taking responsibility for setting the request body, we should block any
            // externally defined Content-Length field (see #5221 for the details)
            val filteredHeaders = this.headers.filterNot { case (k, v) => k.equalsIgnoreCase(HttpHeaders.Names.CONTENT_LENGTH) }

            // extract the content type and the charset
            val charsetOption = Option(HttpUtils.parseCharset(ct))
            val charset = charsetOption.getOrElse {
              StandardCharsets.UTF_8
            }.name()

            // Get the string body given the given charset...
            val stringBody = bytes.decodeString(charset)
            // The Ahc signature calculator uses request.getFormParams() for calculation,
            // so we have to parse it out and add it rather than using setBody.

            val params = for {
              (key, values) <- FormUrlEncodedParser.parse(stringBody).toSeq
              value <- values
            } yield new Param(key, value)
            builder.setFormParams(params.asJava)
            filteredHeaders
          } else {
            builder.setBody(bytes.toArray)
            this.headers
          }
        } catch {
          case e: UnsupportedEncodingException =>
            throw new RuntimeException(e)
        }

        (builder, h)
      case StreamedBody(source) =>
        // If the body has a streaming interface it should be up to the user to provide a manual Content-Length
        // else every content would be Transfer-Encoding: chunked
        // If the Content-Length is -1 Async-Http-Client sets a Transfer-Encoding: chunked
        // If the Content-Length is great than -1 Async-Http-Client will use the correct Content-Length
        val filteredHeaders = this.headers.filterNot { case (k, v) => k.equalsIgnoreCase(HttpHeaders.Names.CONTENT_LENGTH) }
        val contentLength = this.headers.find { case (k, _) => k.equalsIgnoreCase(HttpHeaders.Names.CONTENT_LENGTH) }.map(_._2.head.toLong)

        (builder.setBody(source.map(_.toByteBuffer).runWith(Sink.asPublisher(false)), contentLength.getOrElse(-1L)), filteredHeaders)
    }

    // headers
    for {
      header <- updatedHeaders
      value <- header._2
    } builder.addHeader(header._1, value)

    // Set the signature calculator.
    calc.map {
      case signatureCalculator: org.asynchttpclient.SignatureCalculator =>
        builderWithBody.setSignatureCalculator(signatureCalculator)
      case _ =>
        throw new IllegalStateException("Unknown signature calculator found: use a class that implements SignatureCalculator")
    }

    builderWithBody.build()
  }

  private[libs] def createProxy(wsProxyServer: WSProxyServer): AHCProxyServer = {
    val proxyBuilder = new AHCProxyServer.Builder(wsProxyServer.host, wsProxyServer.port)
    if (wsProxyServer.principal.isDefined) {
      val realmBuilder = new Realm.Builder(wsProxyServer.principal.orNull, wsProxyServer.password.orNull)
      val scheme: Realm.AuthScheme = wsProxyServer.protocol.getOrElse("http").toLowerCase(java.util.Locale.ENGLISH) match {
        case "http" | "https" => Realm.AuthScheme.BASIC
        case "kerberos" => Realm.AuthScheme.KERBEROS
        case "ntlm" => Realm.AuthScheme.NTLM
        case "spnego" => Realm.AuthScheme.SPNEGO
        case _ => scala.sys.error("Unrecognized protocol!")
      }
      realmBuilder.setScheme(scheme)
      wsProxyServer.encoding.foreach(enc => realmBuilder.setCharset(Charset.forName(enc)))
      wsProxyServer.ntlmDomain.foreach(realmBuilder.setNtlmDomain)
      proxyBuilder.setRealm(realmBuilder)
    }

    wsProxyServer.nonProxyHosts.foreach { nonProxyHosts =>
      import scala.collection.JavaConverters._
      proxyBuilder.setNonProxyHosts(nonProxyHosts.asJava)
    }
    proxyBuilder.build()
  }

}
