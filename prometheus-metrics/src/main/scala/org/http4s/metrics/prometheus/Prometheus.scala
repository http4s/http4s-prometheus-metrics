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
import cats.effect.{Resource, Sync}
import io.prometheus.client.*
import org.http4s.metrics.MetricsOps
import org.http4s.metrics.prometheus.MetricsOpsBuilder.DefaultHistogramBuckets

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
object Prometheus {
  def collectorRegistry[F[_]](implicit F: Sync[F]): Resource[F, CollectorRegistry] =
    Resource.make(F.delay(new CollectorRegistry()))(cr => F.blocking(cr.clear()))

  /** Creates a [[MetricsOps]] that supports Prometheus metrics
    *
    * @param registry a metrics collector registry
    * @param prefix a prefix that will be added to all metrics
    * @param responseDurationSecondsHistogramBuckets the buckets of response duration time in second for Histogram.
    */
  def metricsOps[F[_]: Sync](
      registry: CollectorRegistry,
      prefix: String = "org_http4s_server",
      responseDurationSecondsHistogramBuckets: NonEmptyList[Double] = DefaultHistogramBuckets,
  ): Resource[F, MetricsOps[F]] =
    MetricsOpsBuilder
      .default(registry)
      .withPrefix(prefix)
      .withResponseDurationSecondsHistogramBuckets(responseDurationSecondsHistogramBuckets)
      .metricsOps

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
    * @param responseDurationSecondsHistogramBuckets the buckets of response duration time in second for Histogram.
    */
  def metricsOpsWithExemplars[F[_]: Sync](
      registry: CollectorRegistry,
      sampleExemplar: F[Option[Map[String, String]]],
      prefix: String = "org_http4s_server",
      responseDurationSecondsHistogramBuckets: NonEmptyList[Double] = DefaultHistogramBuckets,
  ): Resource[F, MetricsOps[F]] =
    MetricsOpsBuilder
      .default[F](registry)
      .withPrefix(prefix)
      .withSampleExemplar(sampleExemplar)
      .withResponseDurationSecondsHistogramBuckets(responseDurationSecondsHistogramBuckets)
      .metricsOps
}
