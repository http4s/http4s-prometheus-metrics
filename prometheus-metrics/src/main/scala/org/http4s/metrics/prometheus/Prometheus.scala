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
import io.prometheus.client.*
import org.http4s.Method
import org.http4s.Status
import org.http4s.metrics.MetricsOps
import org.http4s.metrics.TerminationType
import org.http4s.metrics.TerminationType.Abnormal
import org.http4s.metrics.TerminationType.Canceled
import org.http4s.metrics.TerminationType.Error
import org.http4s.metrics.TerminationType.Timeout
import org.http4s.metrics.prometheus.Prometheus.registerCollector
import org.http4s.metrics.prometheus.Prometheus.toFlatArray

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
    private val registry: CollectorRegistry,
    private val sampleExemplar: F[Option[Map[String, String]]],
    private val customLabelsAndValues: List[(String, String)],
    private val responseDurationSecondsHistogramBuckets: NonEmptyList[Double],
) { self =>
  private def copy(
      prefix: String = self.prefix,
      registry: CollectorRegistry = self.registry,
      sampleExemplar: F[Option[Map[String, String]]] = self.sampleExemplar,
      customLabelsAndValues: List[(String, String)] = self.customLabelsAndValues,
      responseDurationSecondsHistogramBuckets: NonEmptyList[Double] =
        self.responseDurationSecondsHistogramBuckets,
  ): Prometheus[F] =
    new Prometheus[F](
      prefix = prefix,
      registry = registry,
      sampleExemplar = sampleExemplar,
      customLabelsAndValues = customLabelsAndValues,
      responseDurationSecondsHistogramBuckets = responseDurationSecondsHistogramBuckets,
    )

  def withPrefix(prefix: String): Prometheus[F] = copy(prefix = prefix)
  def withRegister(registry: CollectorRegistry): Prometheus[F] = copy(registry = registry)

  def withSampleExemplar(sampleExemplar: F[Option[Map[String, String]]]): Prometheus[F] =
    copy(sampleExemplar = sampleExemplar)

  def withCustomLabelsAndValues(
      customLabelsAndValues: List[(String, String)]
  ): Prometheus[F] = copy(customLabelsAndValues = customLabelsAndValues)

  def withResponseDurationSecondsHistogramBuckets(
      responseDurationSecondsHistogramBuckets: NonEmptyList[Double]
  ): Prometheus[F] =
    copy(responseDurationSecondsHistogramBuckets = responseDurationSecondsHistogramBuckets)

  /** Build a [[MetricsOps]] that supports Prometheus metrics */
  def build: Resource[F, MetricsOps[F]] = createMetricsCollection.map(createMetricsOps)

  private def createMetricsOps(metrics: MetricsCollection): MetricsOps[F] = {
    val customLabelValues: List[String] = customLabelsAndValues.map(_._2)
    val exemplarLabels: F[Option[Array[String]]] = sampleExemplar.map(_.map(toFlatArray))

    new MetricsOps[F] {
      override def increaseActiveRequests(classifier: Option[String]): F[Unit] =
        Sync[F].delay {
          metrics.activeRequests
            .labels(label(classifier) +: customLabelValues: _*)
            .inc()
        }

      override def decreaseActiveRequests(classifier: Option[String]): F[Unit] =
        Sync[F].delay {
          metrics.activeRequests
            .labels(label(classifier) +: customLabelValues: _*)
            .dec()
        }

      override def recordHeadersTime(
          method: Method,
          elapsed: Long,
          classifier: Option[String],
      ): F[Unit] =
        exemplarLabels.flatMap { exemplarOpt =>
          Sync[F].delay {
            metrics.responseDuration
              .labels(
                label(classifier) +:
                  reportMethod(method) +:
                  Phase.report(Phase.Headers) +:
                  customLabelValues: _*
              )
              .observeWithExemplar(
                SimpleTimer.elapsedSecondsFromNanos(0, elapsed),
                exemplarOpt.orNull: _*
              )
          }
        }

      override def recordTotalTime(
          method: Method,
          status: Status,
          elapsed: Long,
          classifier: Option[String],
      ): F[Unit] =
        exemplarLabels.flatMap { exemplarOpt =>
          Sync[F].delay {
            metrics.responseDuration
              .labels(
                label(classifier) +:
                  reportMethod(method) +:
                  Phase.report(Phase.Body) +:
                  customLabelValues: _*
              )
              .observeWithExemplar(
                SimpleTimer.elapsedSecondsFromNanos(0, elapsed),
                exemplarOpt.orNull: _*
              )
            metrics.requests
              .labels(
                label(classifier) +:
                  reportMethod(method) +:
                  reportStatus(status) +:
                  customLabelValues: _*
              )
              .incWithExemplar(exemplarOpt.orNull: _*)
          }
        }

      override def recordAbnormalTermination(
          elapsed: Long,
          terminationType: TerminationType,
          classifier: Option[String],
      ): F[Unit] =
        terminationType match {
          case Abnormal(e) => recordAbnormal(elapsed, classifier, e)
          case Error(e) => recordError(elapsed, classifier, e)
          case Canceled => recordCanceled(elapsed, classifier)
          case Timeout => recordTimeout(elapsed, classifier)
        }

      private def recordCanceled(elapsed: Long, classifier: Option[String]): F[Unit] =
        exemplarLabels.flatMap { exemplarOpt =>
          Sync[F].delay {
            metrics.abnormalTerminations
              .labels(
                label(classifier) +:
                  AbnormalTermination.report(AbnormalTermination.Canceled) +:
                  label(Option.empty) +:
                  customLabelValues: _*
              )
              .observeWithExemplar(
                SimpleTimer.elapsedSecondsFromNanos(0, elapsed),
                exemplarOpt.orNull: _*
              )
          }
        }

      private def recordAbnormal(
          elapsed: Long,
          classifier: Option[String],
          cause: Throwable,
      ): F[Unit] =
        exemplarLabels.flatMap { exemplarOpt =>
          Sync[F].delay {
            metrics.abnormalTerminations
              .labels(
                label(classifier) +:
                  AbnormalTermination.report(AbnormalTermination.Abnormal) +:
                  label(Option(cause.getClass.getName)) +:
                  customLabelValues: _*
              )
              .observeWithExemplar(
                SimpleTimer.elapsedSecondsFromNanos(0, elapsed),
                exemplarOpt.orNull: _*
              )
          }
        }

      private def recordError(
          elapsed: Long,
          classifier: Option[String],
          cause: Throwable,
      ): F[Unit] =
        exemplarLabels.flatMap { exemplarOpt =>
          Sync[F].delay {
            metrics.abnormalTerminations
              .labels(
                label(classifier) +:
                  AbnormalTermination.report(AbnormalTermination.Error) +:
                  label(Option(cause.getClass.getName)) +:
                  customLabelValues: _*
              )
              .observeWithExemplar(
                SimpleTimer.elapsedSecondsFromNanos(0, elapsed),
                exemplarOpt.orNull: _*
              )
          }
        }

      private def recordTimeout(elapsed: Long, classifier: Option[String]): F[Unit] =
        exemplarLabels.flatMap { exemplarOpt =>
          Sync[F].delay {
            metrics.abnormalTerminations
              .labels(
                label(classifier) +:
                  AbnormalTermination.report(AbnormalTermination.Timeout) +:
                  label(Option.empty) +:
                  customLabelValues: _*
              )
              .observeWithExemplar(
                SimpleTimer.elapsedSecondsFromNanos(0, elapsed),
                exemplarOpt.orNull: _*
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

  private def createMetricsCollection: Resource[F, MetricsCollection] = {
    val customLabels: List[String] = customLabelsAndValues.map(_._1)

    val responseDuration: Resource[F, Histogram] = registerCollector(
      Histogram
        .build()
        .buckets(responseDurationSecondsHistogramBuckets.toList: _*)
        .name(prefix + "_" + "response_duration_seconds")
        .help("Response Duration in seconds.")
        .labelNames("classifier" +: "method" +: "phase" +: customLabels: _*)
        .create(),
      registry,
    )

    val activeRequests: Resource[F, Gauge] = registerCollector(
      Gauge
        .build()
        .name(prefix + "_" + "active_request_count")
        .help("Total Active Requests.")
        .labelNames("classifier" +: customLabels: _*)
        .create(),
      registry,
    )

    val requests: Resource[F, Counter] = registerCollector(
      Counter
        .build()
        .name(prefix + "_" + "request_count")
        .help("Total Requests.")
        .labelNames("classifier" +: "method" +: "status" +: customLabels: _*)
        .create(),
      registry,
    )

    val abnormalTerminations: Resource[F, Histogram] = registerCollector(
      Histogram
        .build()
        .name(prefix + "_" + "abnormal_terminations")
        .help("Total Abnormal Terminations.")
        .labelNames("classifier" +: "termination_type" +: "cause" +: customLabels: _*)
        .create(),
      registry,
    )

    (responseDuration, activeRequests, requests, abnormalTerminations).mapN(MetricsCollection.apply)
  }
}

object Prometheus {
  def collectorRegistry[F[_]](implicit F: Sync[F]): Resource[F, CollectorRegistry] =
    Resource.make(F.delay(new CollectorRegistry()))(cr => F.blocking(cr.clear()))

  /** Creates a [[MetricsOps]] that supports Prometheus metrics
    *
    * @param registry a metrics collector registry
    * @param prefix a prefix that will be added to all metrics
    */
  def metricsOps[F[_]: Sync](
      registry: CollectorRegistry,
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
      registry: CollectorRegistry,
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
      registry: CollectorRegistry,
  )(implicit F: Sync[F]): Resource[F, C] =
    Resource.make(F.blocking(collector.register[C](registry)))(c =>
      F.blocking(registry.unregister(c))
    )

  // https://github.com/prometheus/client_java/blob/parent-0.6.0/simpleclient/src/main/java/io/prometheus/client/Histogram.java#L73
  val DefaultHistogramBuckets: NonEmptyList[Double] =
    NonEmptyList(.005, List(.01, .025, .05, .075, .1, .25, .5, .75, 1, 2.5, 5, 7.5, 10))

  // Prometheus expects exemplars as alternating key-value strings: k1, v1, k2, v2, ...
  private def toFlatArray(m: Map[String, String]): Array[String] = {
    val arr = new Array[String](m.size * 2)
    var i = 0
    m.foreach { case (key, value) =>
      arr(i) = key
      arr(i + 1) = value
      i += 2
    }
    arr
  }

  def default[F[_]: Sync](registry: CollectorRegistry) =
    new Prometheus[F](
      prefix = "org_http4s_server",
      registry = registry,
      sampleExemplar = Option.empty[Map[String, String]].pure,
      customLabelsAndValues = List.empty,
      responseDurationSecondsHistogramBuckets = DefaultHistogramBuckets,
    )

}

final case class MetricsCollection(
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
