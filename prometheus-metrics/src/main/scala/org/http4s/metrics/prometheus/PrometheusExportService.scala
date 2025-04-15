/*
 * Copyright 2018 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.metrics.prometheus

import cats.effect._
import cats.syntax.all._
import io.prometheus.metrics.config.PrometheusProperties
import io.prometheus.metrics.expositionformats.ExpositionFormatWriter
import io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import io.prometheus.metrics.instrumentation.jvm._
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.http4s.Uri.Path
import org.http4s._
import org.http4s.syntax.all._
import org.typelevel.ci._

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/*
 * PrometheusExportService Contains an HttpService
 * ready to be scraped by Prometheus, paired
 * with the PrometheusRegistry that it is creating
 * metrics for, allowing custom metric registration.
 */
final class PrometheusExportService[F[_]] private (
    val routes: HttpRoutes[F],
    val prometheusRegistry: PrometheusRegistry,
)

object PrometheusExportService {
  private val openMetricsTextFormatWriter = new OpenMetricsTextFormatWriter(false, false)
  private val prometheusTextFormatWriter = new PrometheusTextFormatWriter(false)

  private val metricsPath: Path = path"/metrics"

  def apply[F[_]: Sync](prometheusRegistry: PrometheusRegistry): PrometheusExportService[F] =
    new PrometheusExportService(service(prometheusRegistry), prometheusRegistry)

  def format004[F[_]: Sync](prometheusRegistry: PrometheusRegistry): PrometheusExportService[F] =
    new PrometheusExportService(service004(prometheusRegistry), prometheusRegistry)

  def formatOpenmetrics100[F[_]: Sync](
      prometheusRegistry: PrometheusRegistry
  ): PrometheusExportService[F] =
    new PrometheusExportService(serviceOpenmetrics100(prometheusRegistry), prometheusRegistry)

  def build[F[_]: Sync]: Resource[F, PrometheusExportService[F]] =
    for {
      cr <- Prometheus.prometheusRegistry[F]
      _ <- Resource.eval(addDefaults(cr))
    } yield new PrometheusExportService[F](service(cr), cr)

  def generateResponse[F[_]: Sync](
      prometheusRegistry: PrometheusRegistry
  ): F[Response[F]] = generateResponse(None, prometheusRegistry)

  def generateResponse[F[_]: Sync](
      acceptHeader: Option[String],
      prometheusRegistry: PrometheusRegistry,
  ): F[Response[F]] =
    generateResponse(
      chooseContentType(acceptHeader),
      prometheusRegistry,
    )

  private def chooseContentType(acceptsHeader: Option[String]) =

    if (acceptsHeader.exists(openMetricsTextFormatWriter.accepts)) {
      OpenMetricsTextFormatWriter.CONTENT_TYPE
    } else {
      PrometheusTextFormatWriter.CONTENT_TYPE
    }

  private def getWriter(contentType: String): ExpositionFormatWriter = contentType match {
    case OpenMetricsTextFormatWriter.CONTENT_TYPE => openMetricsTextFormatWriter
    case PrometheusTextFormatWriter.CONTENT_TYPE => prometheusTextFormatWriter
    case _ => prometheusTextFormatWriter
  }

  def generateResponse[F[_]: Sync](
      contentType: String,
      prometheusRegistry: PrometheusRegistry,
  ): F[Response[F]] = for {
    writer <- Sync[F].delay(getWriter(contentType))
    text <-
      Resource.fromAutoCloseable(Sync[F].delay(new ByteArrayOutputStream())).use { os =>
        Sync[F]
          .blocking {
            writer.write(os, prometheusRegistry.scrape())
            os.flush()
            os.toString(StandardCharsets.UTF_8.name())
          }

      }
  } yield Response[F](Status.Ok)
    .withEntity(text)
    .withHeaders(Header.Raw(ci"Content-Type", writer.getContentType))

  def service[F[_]: Sync](prometheusRegistry: PrometheusRegistry): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && req.pathInfo == metricsPath =>
        generateResponse(req.headers.get(ci"accept").map(_.head.value), prometheusRegistry)
    }

  def service004[F[_]: Sync](prometheusRegistry: PrometheusRegistry): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && req.pathInfo == metricsPath =>
        generateResponse(PrometheusTextFormatWriter.CONTENT_TYPE, prometheusRegistry)
    }

  def serviceOpenmetrics100[F[_]: Sync](prometheusRegistry: PrometheusRegistry): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && req.pathInfo == metricsPath =>
        generateResponse(OpenMetricsTextFormatWriter.CONTENT_TYPE, prometheusRegistry)
    }

  private val config = PrometheusProperties.get()

  def addDefaults[F[_]](cr: PrometheusRegistry)(implicit F: Sync[F]): F[Unit] =
    F.delay(JvmMetrics.builder(config).register(cr))
}
