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

import cats.data.NonEmptyList
import cats.effect.Resource
import cats.effect.Sync
import cats.syntax.all.*
import io.prometheus.metrics.core.metrics.*
import io.prometheus.metrics.model.registry.Collector
import io.prometheus.metrics.model.snapshots.Labels
import org.http4s.Method
import org.http4s.Status
import org.http4s.metrics.CustomLabels
import org.http4s.metrics.CustomMetricsOps
import org.http4s.metrics.EmptyCustomLabels
import org.http4s.metrics.MetricsOps
import org.http4s.metrics.TerminationType
import org.http4s.metrics.TerminationType.Abnormal
import org.http4s.metrics.TerminationType.Canceled
import org.http4s.metrics.TerminationType.Error
import org.http4s.metrics.TerminationType.Timeout
import org.http4s.metrics.prometheus.Prometheus.registerCollector
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.http4s.util.SizedSeq

/** [[MetricsOps]] algebra capable of recording Prometheus metrics
  *
  * For example, the following code would wrap a [[org.http4s.HttpRoutes]] with a `org.http4s.server.middleware.Metrics`
  * that records metrics to a given metric registry.
  * {{{
  * import cats.effect.{Resource, IO}
  * import org.http4s.server.middleware.Metrics
  * import org.http4s.metrics.Prometheus
  *
  * val meteredRoutes: Resource[IO, HttpRoutes[IO]] =
  *   Prometheus.metricsOps[IO](registry, "server").map(ops => Metrics[IO](ops)(testRoutes))
  * }}}
  *
  * Analogously, the following code would wrap a `org.http4s.client.Client` with a `org.http4s.client.middleware.Metrics`
  * that records metrics to a given metric registry, classifying the metrics by HTTP method.
  * {{{
  * import cats.effect.{Resource, IO}
  * import org.http4s.client.middleware.Metrics
  * import org.http4s.metrics.Prometheus
  *
  * val classifierFunc = (r: Request[IO]) => Some(r.method.toString.toLowerCase)
  * val meteredClient: Resource[IO, Client[IO]] =
  *   Prometheus.metricsOps[IO](registry, "client").map(ops => Metrics[IO](ops, classifierFunc)(client))
  * }}}
  *
  * Registers the following metrics:
  *
  * {prefix}_response_duration_seconds{labels=classifier,method,phase} - Histogram
  *
  * {prefix}_active_request_count{labels=classifier} - Gauge
  *
  * {prefix}_request_count{labels=classifier,method,status} - Counter
  *
  * {prefix}_abnormal_terminations{labels=classifier,termination_type} - Histogram
  *
  * Labels --
  *
  * method: Enumeration
  * values: get, put, post, head, move, options, trace, connect, delete, other
  *
  * phase: Enumeration
  * values: headers, body
  *
  * code: Enumeration
  * values:  1xx, 2xx, 3xx, 4xx, 5xx
  *
  * termination_type: Enumeration
  * values: abnormal, error, timeout
  *
  * custom labels: custom labels, provided by customLabelsAndValues.map(_._1)
  * values: custom label values, provided by customLabelsAndValues.map(_._2)
  */
final class Prometheus[F[_]: Sync] private (
    private val prefix: String,
    private val registry: PrometheusRegistry,
    private val sampleExemplar: F[Option[Map[String, String]]],
    private val responseDurationSecondsHistogramBuckets: NonEmptyList[Double],
) { self =>
  private def copy(
      prefix: String = self.prefix,
      registry: PrometheusRegistry = self.registry,
      sampleExemplar: F[Option[Map[String, String]]] = self.sampleExemplar,
      responseDurationSecondsHistogramBuckets: NonEmptyList[Double] =
        self.responseDurationSecondsHistogramBuckets,
  ): Prometheus[F] =
    new Prometheus[F](
      prefix = prefix,
      registry = registry,
      sampleExemplar = sampleExemplar,
      responseDurationSecondsHistogramBuckets = responseDurationSecondsHistogramBuckets,
    )

  def withPrefix(prefix: String): Prometheus[F] = copy(prefix = prefix)
  def withRegister(registry: PrometheusRegistry): Prometheus[F] = copy(registry = registry)

  def withSampleExemplar(sampleExemplar: F[Option[Map[String, String]]]): Prometheus[F] =
    copy(sampleExemplar = sampleExemplar)

  def withResponseDurationSecondsHistogramBuckets(
      responseDurationSecondsHistogramBuckets: NonEmptyList[Double]
  ): Prometheus[F] =
    copy(responseDurationSecondsHistogramBuckets = responseDurationSecondsHistogramBuckets)

  /** Build a [[MetricsOps]] that supports Prometheus metrics */
  def build: Resource[F, MetricsOps[F]] = buildCustomMetricsOps(EmptyCustomLabels())

  def buildCustomMetricsOps[SL <: SizedSeq[String]](
      pCustomLabels: CustomLabels[SL]
  ): Resource[F, CustomMetricsOps[F, SL]] =
    createMetricsCollection(pCustomLabels).map(createMetricsOps(pCustomLabels))

  private def createMetricsOps[SL <: SizedSeq[String]](pCustomLabels: CustomLabels[SL])(
      metrics: MetricsCollection[SL]
  ): CustomMetricsOps[F, SL] = {
    val exemplarLabels: F[Labels] =
      sampleExemplar.map(
        _.fold(Labels.EMPTY)(examples => Labels.of(examples.keys.toArray, examples.values.toArray))
      )

    new CustomMetricsOps[F, SL] {
      override def definingCustomLabels: CustomLabels[SL] = pCustomLabels

      override def increaseActiveRequests(
          classifier: Option[String],
          customLabelValues: SL,
      ): F[Unit] =
        Sync[F].delay {
          metrics.activeRequests
            .labelValues(label(classifier) +: customLabelValues.toSeq: _*)
            .inc()
        }

      override def decreaseActiveRequests(
          classifier: Option[String],
          customLabelValues: SL,
      ): F[Unit] =
        Sync[F].delay {
          metrics.activeRequests
            .labelValues(label(classifier) +: customLabelValues.toSeq: _*)
            .dec()
        }

      override def recordHeadersTime(
          method: Method,
          elapsed: Long,
          classifier: Option[String],
          customLabelValues: SL,
      ): F[Unit] =
        exemplarLabels.flatMap { exemplar =>
          Sync[F].delay {
            metrics.responseDuration
              .labelValues(
                label(classifier) +:
                  reportMethod(method) +:
                  Phase.report(Phase.Headers) +:
                  customLabelValues.toSeq: _*
              )
              .observeWithExemplar(
                Prometheus.elapsedSecondsFromNanos(0, elapsed),
                exemplar,
              )
          }
        }

      override def recordTotalTime(
          method: Method,
          status: Status,
          elapsed: Long,
          classifier: Option[String],
          customLabelValues: SL,
      ): F[Unit] =
        exemplarLabels.flatMap { exemplar =>
          Sync[F].delay {
            metrics.responseDuration
              .labelValues(
                label(classifier) +:
                  reportMethod(method) +:
                  Phase.report(Phase.Body) +:
                  customLabelValues.toSeq: _*
              )
              .observeWithExemplar(
                Prometheus.elapsedSecondsFromNanos(0, elapsed),
                exemplar,
              )
            metrics.requests
              .labelValues(
                label(classifier) +:
                  reportMethod(method) +:
                  reportStatus(status) +:
                  customLabelValues.toSeq: _*
              )
              .incWithExemplar(exemplar)
          }
        }

      override def recordAbnormalTermination(
          elapsed: Long,
          terminationType: TerminationType,
          classifier: Option[String],
          customLabelValues: SL,
      ): F[Unit] =
        terminationType match {
          case Abnormal(e) => recordAbnormal(elapsed, classifier, customLabelValues, e)
          case Error(e) => recordError(elapsed, classifier, customLabelValues, e)
          case Canceled => recordCanceled(elapsed, classifier, customLabelValues)
          case Timeout => recordTimeout(elapsed, classifier, customLabelValues)
        }

      private def recordCanceled(
          elapsed: Long,
          classifier: Option[String],
          customLabelValues: SL,
      ): F[Unit] =
        exemplarLabels.flatMap { exemplar =>
          Sync[F].delay {
            metrics.abnormalTerminations
              .labelValues(
                label(classifier) +:
                  AbnormalTermination.report(AbnormalTermination.Canceled) +:
                  label(Option.empty) +:
                  customLabelValues.toSeq: _*
              )
              .observeWithExemplar(
                Prometheus.elapsedSecondsFromNanos(0, elapsed),
                exemplar,
              )
          }
        }

      private def recordAbnormal(
          elapsed: Long,
          classifier: Option[String],
          customLabelValues: SL,
          cause: Throwable,
      ): F[Unit] =
        exemplarLabels.flatMap { exemplar =>
          Sync[F].delay {
            metrics.abnormalTerminations
              .labelValues(
                label(classifier) +:
                  AbnormalTermination.report(AbnormalTermination.Abnormal) +:
                  label(Option(cause.getClass.getName)) +:
                  customLabelValues.toSeq: _*
              )
              .observeWithExemplar(
                Prometheus.elapsedSecondsFromNanos(0, elapsed),
                exemplar,
              )
          }
        }

      private def recordError(
          elapsed: Long,
          classifier: Option[String],
          customLabelValues: SL,
          cause: Throwable,
      ): F[Unit] =
        exemplarLabels.flatMap { exemplar =>
          Sync[F].delay {
            metrics.abnormalTerminations
              .labelValues(
                label(classifier) +:
                  AbnormalTermination.report(AbnormalTermination.Error) +:
                  label(Option(cause.getClass.getName)) +:
                  customLabelValues.toSeq: _*
              )
              .observeWithExemplar(
                Prometheus.elapsedSecondsFromNanos(0, elapsed),
                exemplar,
              )
          }
        }

      private def recordTimeout(
          elapsed: Long,
          classifier: Option[String],
          customLabelValues: SL,
      ): F[Unit] =
        exemplarLabels.flatMap { exemplar =>
          Sync[F].delay {
            metrics.abnormalTerminations
              .labelValues(
                label(classifier) +:
                  AbnormalTermination.report(AbnormalTermination.Timeout) +:
                  label(Option.empty) +:
                  customLabelValues.toSeq: _*
              )
              .observeWithExemplar(
                Prometheus.elapsedSecondsFromNanos(0, elapsed),
                exemplar,
              )
          }
        }

      private def label(value: Option[String]): String = value.getOrElse("")

      private def reportStatus(status: Status): String =
        status.code match {
          case hundreds if hundreds < 200 => "1xx"
          case twohundreds if twohundreds < 300 => "2xx"
          case threehundreds if threehundreds < 400 => "3xx"
          case fourhundreds if fourhundreds < 500 => "4xx"
          case _ => "5xx"
        }

      private def reportMethod(m: Method): String =
        m match {
          case Method.GET => "get"
          case Method.PUT => "put"
          case Method.POST => "post"
          case Method.PATCH => "patch"
          case Method.HEAD => "head"
          case Method.MOVE => "move"
          case Method.OPTIONS => "options"
          case Method.TRACE => "trace"
          case Method.CONNECT => "connect"
          case Method.DELETE => "delete"
          case _ => "other"
        }
    }
  }

  private def createMetricsCollection[SL <: SizedSeq[String]](
      pCustomLabels: CustomLabels[SL]
  ): Resource[F, MetricsCollection[SL]] = {
    val customLabels = pCustomLabels.labels

    val responseDuration: Resource[F, Histogram] = registerCollector(
      Histogram
        .builder()
        .classicUpperBounds(responseDurationSecondsHistogramBuckets.toList: _*)
        .name(prefix + "_" + "response_duration_seconds")
        .help("Response Duration in seconds.")
        .labelNames("classifier" +: "method" +: "phase" +: customLabels.toSeq: _*)
        .build(),
      registry,
    )

    val activeRequests: Resource[F, Gauge] = registerCollector(
      Gauge
        .builder()
        .name(prefix + "_" + "active_request_count")
        .help("Total Active Requests.")
        .labelNames("classifier" +: customLabels.toSeq: _*)
        .build(),
      registry,
    )

    val requests: Resource[F, Counter] = registerCollector(
      Counter
        .builder()
        .name(prefix + "_" + "request_count")
        .help("Total Requests.")
        .labelNames("classifier" +: "method" +: "status" +: customLabels.toSeq: _*)
        .build(),
      registry,
    )

    val abnormalTerminations: Resource[F, Histogram] = registerCollector(
      Histogram
        .builder()
        .name(prefix + "_" + "abnormal_terminations")
        .help("Total Abnormal Terminations.")
        .labelNames("classifier" +: "termination_type" +: "cause" +: customLabels.toSeq: _*)
        .build(),
      registry,
    )

    (responseDuration, activeRequests, requests, abnormalTerminations).mapN(MetricsCollection.apply)
  }
}

object Prometheus {
  val NANOSECONDS_PER_SECOND = 1e9
  def elapsedSecondsFromNanos(startNanos: Long, endNanos: Long): Double =
    (endNanos - startNanos) / NANOSECONDS_PER_SECOND
  def prometheusRegistry[F[_]](implicit F: Sync[F]): Resource[F, PrometheusRegistry] =
    Resource.make(F.delay(new PrometheusRegistry()))(cr => F.blocking(cr.clear()))

  /** Creates a [[MetricsOps]] that supports Prometheus metrics
    *
    * @param registry a metrics collector registry
    * @param prefix a prefix that will be added to all metrics
    */
  def metricsOps[F[_]: Sync](
      registry: PrometheusRegistry,
      prefix: String = "org_http4s_server",
      responseDurationSecondsHistogramBuckets: NonEmptyList[Double] = DefaultHistogramBuckets,
  ): Resource[F, MetricsOps[F]] =
    Prometheus
      .default(registry)
      .withPrefix(prefix)
      .withResponseDurationSecondsHistogramBuckets(responseDurationSecondsHistogramBuckets)
      .build

  /** Creates a [[MetricsOps]] that supports Prometheus metrics and records exemplars.
    *
    * Warning: The sampler effect is responsible for producing exemplar labels that are valid for the underlying
    * implementation as errors happening during metric recording will not be handled! For Prometheus version < 1.0,
    * this means the combined length of keys and values may not exceed 128 characters and the parts must adhere
    * to the label regex Prometheus defines.
    *
    * @param registry a metrics collector registry
    * @param sampleExemplar an effect that returns the corresponding exemplar labels
    * @param prefix a prefix that will be added to all metrics
    */
  def metricsOpsWithExemplars[F[_]: Sync](
      registry: PrometheusRegistry,
      sampleExemplar: F[Option[Map[String, String]]],
      prefix: String = "org_http4s_server",
      responseDurationSecondsHistogramBuckets: NonEmptyList[Double] = DefaultHistogramBuckets,
  ): Resource[F, MetricsOps[F]] =
    Prometheus
      .default[F](registry)
      .withPrefix(prefix)
      .withSampleExemplar(sampleExemplar)
      .withResponseDurationSecondsHistogramBuckets(responseDurationSecondsHistogramBuckets)
      .build

  private[prometheus] def registerCollector[F[_], C <: Collector](
      collector: C,
      registry: PrometheusRegistry,
  )(implicit F: Sync[F]): Resource[F, C] =
    Resource.make(F.blocking(registry.register(collector)).as(collector))(c =>
      F.blocking(registry.unregister(c))
    )

  // https://github.com/prometheus/client_java/blob/parent-0.6.0/simpleclient/src/main/java/io/prometheus/client/Histogram.java#L73
  val DefaultHistogramBuckets: NonEmptyList[Double] =
    NonEmptyList(.005, List(.01, .025, .05, .075, .1, .25, .5, .75, 1, 2.5, 5, 7.5, 10))

  def default[F[_]: Sync](registry: PrometheusRegistry) =
    new Prometheus[F](
      prefix = "org_http4s_server",
      registry = registry,
      sampleExemplar = Option.empty[Map[String, String]].pure,
      responseDurationSecondsHistogramBuckets = DefaultHistogramBuckets,
    )

}

final case class MetricsCollection[SL <: SizedSeq[String]](
    responseDuration: Histogram,
    activeRequests: Gauge,
    requests: Counter,
    abnormalTerminations: Histogram,
)

private sealed trait Phase
private object Phase {
  case object Headers extends Phase
  case object Body extends Phase
  def report(s: Phase): String =
    s match {
      case Headers => "headers"
      case Body => "body"
    }
}

private sealed trait AbnormalTermination
private object AbnormalTermination {
  case object Abnormal extends AbnormalTermination
  case object Error extends AbnormalTermination
  case object Timeout extends AbnormalTermination
  case object Canceled extends AbnormalTermination
  def report(t: AbnormalTermination): String =
    t match {
      case Abnormal => "abnormal"
      case Timeout => "timeout"
      case Error => "error"
      case Canceled => "cancel"
    }
}
