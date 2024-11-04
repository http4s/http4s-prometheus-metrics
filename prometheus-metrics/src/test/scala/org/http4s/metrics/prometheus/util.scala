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

import cats.effect.Clock
import cats.effect.IO
import cats.effect.Sync
import fs2.Stream
import io.prometheus.client.CollectorRegistry
import org.http4s.Method.GET
import org.http4s.Request
import org.http4s.Response
import org.http4s.dsl.io._
import org.http4s.metrics.CustomLabels
import org.http4s.metrics.EmptyCustomLabels
import org.http4s.util.SizedSeq
import org.http4s.util.SizedSeq3

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

object util {
  val providerLabels: SizedSeq3[String] = SizedSeq3("provider", "customLabel2", "customLabel3")
  val providerLabelValues: SizedSeq3[String] = SizedSeq3("", "", "")
  val providerCustomLabels: CustomLabels[SizedSeq3[String]] = new CustomLabels[SizedSeq3[String]] {
    override def labels: SizedSeq3[String] = providerLabels
    override def values: SizedSeq3[String] = providerLabelValues
  }
  val paypalLabelValues: SizedSeq3[String] =
    SizedSeq3("Paypal", "test-custom-label12", "test-custom-label13")
  val paypalProviderLabels: CustomLabels[SizedSeq3[String]] = new CustomLabels[SizedSeq3[String]] {
    override def labels: SizedSeq3[String] = providerLabels
    override def values: SizedSeq3[String] = paypalLabelValues
  }

  def stub: PartialFunction[Request[IO], IO[Response[IO]]] = {
    case (GET | POST | PUT | DELETE) -> Root / "ok" =>
      Ok("200 OK")
    case GET -> Root / "bad-request" =>
      BadRequest("400 Bad Request")
    case GET -> Root / "internal-server-error" =>
      InternalServerError("500 Internal Server Error")
    case GET -> Root / "error" =>
      IO.raiseError[Response[IO]](new IOException("error"))
    case GET -> Root / "timeout" =>
      IO.raiseError[Response[IO]](new TimeoutException("request timed out"))
    case GET -> Root / "abnormal-termination" =>
      Ok("200 OK").map(
        _.withBodyStream(Stream.raiseError[IO](new RuntimeException("Abnormal termination")))
      )
    case GET -> Root / "never" =>
      IO.never
    case _ =>
      NotFound("404 Not Found")
  }

  def count(
      registry: CollectorRegistry,
      name: String,
      prefix: String,
      method: String = "get",
      classifier: String = "",
      cause: String = "",
  ): Double =
    cntWithCustLbl(
      registry,
      name,
      prefix,
      method,
      classifier,
      cause,
    )(EmptyCustomLabels())

  def cntWithCustLbl(
      registry: CollectorRegistry,
      name: String,
      prefix: String,
      method: String = "get",
      classifier: String = "",
      cause: String = "",
  )(
      pCustomLabels: CustomLabels[SizedSeq[String]]
  ): Double = {
    val customLabels = pCustomLabels.labels.toSeq
    val customValues = pCustomLabels.values.toSeq
    name match {
      case "active_requests" =>
        registry.getSampleValue(
          s"${prefix}_active_request_count",
          Array("classifier") ++ customLabels,
          Array(classifier) ++ customValues,
        )
      case "2xx_responses" =>
        registry
          .getSampleValue(
            s"${prefix}_request_count_total",
            Array("classifier", "method", "status") ++ customLabels.toSeq,
            Array(classifier, method, "2xx") ++ customValues.toSeq,
          )
      case "2xx_headers_duration" =>
        registry.getSampleValue(
          s"${prefix}_response_duration_seconds_sum",
          Array("classifier", "method", "phase") ++ customLabels,
          Array(classifier, method, "headers") ++ customValues,
        )
      case "2xx_total_duration" =>
        registry.getSampleValue(
          s"${prefix}_response_duration_seconds_sum",
          Array("classifier", "method", "phase") ++ customLabels,
          Array(classifier, method, "body") ++ customValues,
        )
      case "4xx_responses" =>
        registry
          .getSampleValue(
            s"${prefix}_request_count_total",
            Array("classifier", "method", "status") ++ customLabels,
            Array(classifier, method, "4xx") ++ customValues,
          )
      case "4xx_headers_duration" =>
        registry.getSampleValue(
          s"${prefix}_response_duration_seconds_sum",
          Array("classifier", "method", "phase") ++ customLabels,
          Array(classifier, method, "headers") ++ customValues,
        )
      case "4xx_total_duration" =>
        registry.getSampleValue(
          s"${prefix}_response_duration_seconds_sum",
          Array("classifier", "method", "phase") ++ customLabels,
          Array(classifier, method, "body") ++ customValues,
        )
      case "5xx_responses" =>
        registry
          .getSampleValue(
            s"${prefix}_request_count_total",
            Array("classifier", "method", "status") ++ customLabels,
            Array(classifier, method, "5xx") ++ customValues,
          )
      case "5xx_headers_duration" =>
        registry.getSampleValue(
          s"${prefix}_response_duration_seconds_sum",
          Array("classifier", "method", "phase") ++ customLabels,
          Array(classifier, method, "headers") ++ customValues,
        )
      case "5xx_total_duration" =>
        registry.getSampleValue(
          s"${prefix}_response_duration_seconds_sum",
          Array("classifier", "method", "phase") ++ customLabels,
          Array(classifier, method, "body") ++ customValues,
        )
      case "errors" =>
        registry.getSampleValue(
          s"${prefix}_abnormal_terminations_count",
          Array("classifier", "termination_type", "cause") ++ customLabels,
          Array(classifier, "error", cause) ++ customValues,
        )
      case "timeouts" =>
        registry.getSampleValue(
          s"${prefix}_abnormal_terminations_count",
          Array("classifier", "termination_type", "cause") ++ customLabels,
          Array(classifier, "timeout", cause) ++ customValues,
        )
      case "abnormal_terminations" =>
        registry.getSampleValue(
          s"${prefix}_abnormal_terminations_count",
          Array("classifier", "termination_type", "cause") ++ customLabels,
          Array(classifier, "abnormal", cause) ++ customValues,
        )
      case "cancels" =>
        registry.getSampleValue(
          s"${prefix}_abnormal_terminations_count",
          Array("classifier", "termination_type", "cause") ++ customLabels,
          Array(classifier, "cancel", cause) ++ customValues,
        )
    }
  }

  object FakeClock {
    def apply[F[_]: Sync]: Clock[F] =
      new Clock[F] {
        private var count = 0L

        override def applicative: cats.Applicative[F] = Sync[F]

        override def realTime: F[FiniteDuration] =
          Sync[F].delay {
            count += 50
            FiniteDuration(count, TimeUnit.MILLISECONDS)
          }

        override def monotonic: F[FiniteDuration] =
          Sync[F].delay {
            count += 50
            FiniteDuration(count, TimeUnit.MILLISECONDS)
          }
      }
  }
}
