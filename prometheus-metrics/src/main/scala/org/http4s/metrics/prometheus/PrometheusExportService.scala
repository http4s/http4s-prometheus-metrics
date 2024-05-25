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
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot._
import org.http4s.Uri.Path
import org.http4s._
import org.http4s.syntax.all._
import org.typelevel.ci._

/*
 * PrometheusExportService Contains an HttpService
 * ready to be scraped by Prometheus, paired
 * with the CollectorRegistry that it is creating
 * metrics for, allowing custom metric registration.
 */
final class PrometheusExportService[F[_]] private (
    val routes: HttpRoutes[F],
    val collectorRegistry: CollectorRegistry,
)

object PrometheusExportService {

  private val metricsPath: Path = path"/metrics"

  def apply[F[_]: Sync](collectorRegistry: CollectorRegistry): PrometheusExportService[F] =
    new PrometheusExportService(service(collectorRegistry), collectorRegistry)

  def format004[F[_]: Sync](collectorRegistry: CollectorRegistry): PrometheusExportService[F] =
    new PrometheusExportService(service004(collectorRegistry), collectorRegistry)

  def formatOpenmetrics100[F[_]: Sync](
      collectorRegistry: CollectorRegistry
  ): PrometheusExportService[F] =
    new PrometheusExportService(serviceOpenmetrics100(collectorRegistry), collectorRegistry)

  def build[F[_]: Sync]: Resource[F, PrometheusExportService[F]] =
    for {
      cr <- Prometheus.collectorRegistry[F]
      _ <- addDefaults(cr)
    } yield new PrometheusExportService[F](service(cr), cr)

  def generateResponse[F[_]: Sync](
      collectorRegistry: CollectorRegistry
  ): F[Response[F]] = generateResponse(None, collectorRegistry)

  def generateResponse[F[_]: Sync](
      acceptHeader: Option[String],
      collectorRegistry: CollectorRegistry,
  ): F[Response[F]] =
    generateResponse(
      acceptHeader.fold(TextFormat.CONTENT_TYPE_004)(TextFormat.chooseContentType),
      collectorRegistry,
    )

  def generateResponse[F[_]: Sync](
      contentType: String,
      collectorRegistry: CollectorRegistry,
  ): F[Response[F]] = for {
    parsedContentType <- Sync[F].delay(TextFormat.chooseContentType(contentType))
    text <- Sync[F]
      .blocking {
        val writer = new NonSafepointingStringWriter()
        TextFormat.writeFormat(
          parsedContentType,
          writer,
          collectorRegistry.metricFamilySamples,
        )
        writer.toString
      }
  } yield Response[F](Status.Ok)
    .withEntity(text)
    .withHeaders(Header.Raw(ci"Content-Type", parsedContentType))

  def service[F[_]: Sync](collectorRegistry: CollectorRegistry): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && req.pathInfo == metricsPath =>
        generateResponse(req.headers.get(ci"accept").map(_.head.value), collectorRegistry)
    }

  def service004[F[_]: Sync](collectorRegistry: CollectorRegistry): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && req.pathInfo == metricsPath =>
        generateResponse(TextFormat.CONTENT_TYPE_004, collectorRegistry)
    }

  def serviceOpenmetrics100[F[_]: Sync](collectorRegistry: CollectorRegistry): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && req.pathInfo == metricsPath =>
        generateResponse(TextFormat.CONTENT_TYPE_OPENMETRICS_100, collectorRegistry)
    }

  def addDefaults[F[_]: Sync](cr: CollectorRegistry): Resource[F, Unit] =
    for {
      _ <- Prometheus.registerCollector(new StandardExports(), cr)
      _ <- Prometheus.registerCollector(new MemoryPoolsExports(), cr)
      _ <- Prometheus.registerCollector(new BufferPoolsExports(), cr)
      _ <- Prometheus.registerCollector(new GarbageCollectorExports(), cr)
      _ <- Prometheus.registerCollector(new ThreadExports(), cr)
      _ <- Prometheus.registerCollector(new ClassLoadingExports(), cr)
      _ <- Prometheus.registerCollector(new VersionInfoExports(), cr)
      _ <- Prometheus.registerCollector(new MemoryAllocationExports(), cr)
    } yield ()
}
