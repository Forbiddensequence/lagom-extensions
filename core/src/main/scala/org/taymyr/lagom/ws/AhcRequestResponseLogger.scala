package org.taymyr.lagom.ws

import akka.util.ByteString
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.PathNotFoundException
import com.jayway.jsonpath.JsonPath.using
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import play.api.libs.ws.EmptyBody
import play.api.libs.ws.InMemoryBody
import play.api.libs.ws.SourceBody
import play.api.libs.ws.StandaloneWSResponse
import play.api.libs.ws.WSRequestExecutor
import play.api.libs.ws.WSRequestFilter
import play.api.libs.ws.ahc.CurlFormat
import play.api.libs.ws.ahc.StandaloneAhcWSRequest

import java.util.UUID
import java.util.UUID.randomUUID
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

class AhcRequestResponseLogger(loggingSettings: LoggingSettings, logger: Logger)(implicit ec: ExecutionContext)
    extends WSRequestFilter
    with CurlFormat {

  private val configuration =
    Configuration.builder
      .jsonProvider(new JacksonJsonNodeJsonProvider)
      .mappingProvider(new JacksonMappingProvider)
      .build

  override def apply(executor: WSRequestExecutor): WSRequestExecutor = WSRequestExecutor { request =>
    val eventualResponse = executor(request)
    val correlationId    = randomUUID()
    val r                = request.asInstanceOf[StandaloneAhcWSRequest]
    val url              = r.buildRequest().getUrl
    if (loggingSettings.skipUrls.exists { _.findFirstIn(url).nonEmpty }) {
      eventualResponse
    } else {
      val shadowingSettings = loggingSettings.fieldsShadowing.find(p => p.url.findFirstIn(url).nonEmpty)
      logRequest(r, url, correlationId, shadowingSettings)
      eventualResponse.map { response =>
        logResponse(response, url, correlationId, shadowingSettings)
        response
      }
    }
  }

  private def logRequest(
      request: StandaloneAhcWSRequest,
      url: String,
      correlationId: UUID,
      shadowingConfig: Option[ShadowingSettings]
  ): Unit = {
    val sb = new StringBuilder(s"Request to $url")
    sb.append("\n")
      .append(s"Request correlation ID: $correlationId")
      .append("\n")
      .append(toCurl(shadowRequest(request, shadowingConfig)))
    logger.info(sb.toString())
  }

  private def shadowRequest(
      request: StandaloneAhcWSRequest,
      shadowingConfig: Option[ShadowingSettings]
  ): StandaloneAhcWSRequest = {
    val jsonString: String = request.body match {
      case InMemoryBody(byteString) =>
        byteString.decodeString(findCharset(request))
      case EmptyBody     => null
      case SourceBody(_) => null
    }
    val processedJsonRequest = shadowStringJson(jsonString, shadowingConfig, _.requestPatterns)
    request
      .withBody(
        request.body match {
          case InMemoryBody(_) =>
            InMemoryBody(ByteString(processedJsonRequest, findCharset(request)))
          case _ => request.body
        }
      )
      .asInstanceOf[StandaloneAhcWSRequest]
  }

  private def shadowStringJson(
      jsonString: String,
      shadowingConfig: Option[ShadowingSettings],
      seqSelector: ShadowingSettings => Seq[String]
  ): String = {
    val resultJson: String = if (shadowingConfig.nonEmpty && jsonString != null) {
      replaceAllEntrance(jsonString, seqSelector.apply(shadowingConfig.get), shadowingConfig.get.replacingSymbols)
    } else jsonString
    resultJson
  }

  private def shadowResponse(response: String, shadowingConfig: Option[ShadowingSettings]): String =
    shadowStringJson(response, shadowingConfig, _.responsePatterns)

  @tailrec
  private def replaceAllEntrance(jsonString: String, regexps: Seq[String], replacingValue: String): String =
    if (regexps.nonEmpty)
      replaceAllEntrance(replaceJson(jsonString, regexps.head, replacingValue), regexps.drop(1), replacingValue)
    else
      jsonString

  private def replaceJson(jsonString: String, jsonPathExp: String, replacingValue: String): String =
    try {
      using(configuration).parse(jsonString).set(jsonPathExp, replacingValue).jsonString
    } catch {
      case _: Throwable => jsonString
    }

  private def logResponse(
      response: StandaloneWSResponse,
      url: String,
      correlationId: UUID,
      shadowingConfig: Option[ShadowingSettings]
  ): Unit = {
    val sb = new StringBuilder(s"Response from $url")
    sb.append("\n")
      .append(s"Request correlation ID: $correlationId")
      .append("\n")
      .append(s"Response actual URI: ${response.uri}")
      .append("\n")
      .append(s"Response status code: ${response.status}")
      .append("\n")
      .append(s"Response status text: ${response.statusText}")
      .append("\n")

    if (response.headers.nonEmpty) {
      sb.append("Response headers:")
        .append("\n")
      response.headers.foreach {
        case (header, values) =>
          values.foreach { value =>
            sb.append(s"    $header: $value")
            sb.append("\n")
          }
      }
    }

    if (response.cookies.nonEmpty) {
      sb.append("Response cookies:")
      response.cookies.foreach { cookie =>
        sb.append(s"    ${cookie.name}: ${cookie.value}")
        sb.append("\n")
      }
    }

    Option(response.body) match {
      case Some(body) =>
        sb.append("Response body: ").append(shadowResponse(body, shadowingConfig))
      case None => // do nothing
    }

    logger.info(sb.toString())
  }
}

object AhcRequestResponseLogger {

  private val logger = LoggerFactory.getLogger("org.taymyr.lagom.ws.AhcRequestResponseLogger")

  def apply(loggingSettings: LoggingSettings)(implicit ec: ExecutionContext): AhcRequestResponseLogger =
    new AhcRequestResponseLogger(loggingSettings, logger)

  def apply(loggingSettings: LoggingSettings, logger: Logger)(implicit ec: ExecutionContext): AhcRequestResponseLogger =
    new AhcRequestResponseLogger(loggingSettings, logger)
}
