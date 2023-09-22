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
import io.prometheus.client.CollectorRegistry
import munit.CatsEffectSuite
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.client.middleware.Metrics
import org.http4s.metrics.prometheus.util._

class PrometheusExemplarsSuite extends CatsEffectSuite {
  val client: Client[IO] = Client.fromHttpApp[IO](HttpApp[IO](stub))

  meteredClient(exemplar = Map("trace_id" -> "123")).test(
    "A http client with a prometheus metrics middleware should sample an exemplar"
  ) { case (registry, client) =>
    client.expect[String]("/ok").attempt.map { resp =>
      val filter = new java.util.HashSet[String]()
      filter.add("exemplars_request_count_total")
      val exemplar = registry
        .filteredMetricFamilySamples(filter)
        .nextElement()
        .samples
        .get(0)
        .exemplar

      assertEquals(exemplar.getLabelName(0), "trace_id")
      assertEquals(exemplar.getLabelValue(0), "123")
      assertEquals(resp, Right("200 OK"))
    }
  }

  private def buildMeteredClient(
      exemplar: Map[String, String]
  ): Resource[IO, (CollectorRegistry, Client[IO])] = {
    implicit val clock: Clock[IO] = FakeClock[IO]

    for {
      registry <- Prometheus.collectorRegistry[IO]
      metrics <- Prometheus
        .metricsOpsWithExemplars[IO](registry, IO.pure(Some(exemplar)), "exemplars")
    } yield (registry, Metrics(metrics)(client))
  }

  def meteredClient(
      exemplar: Map[String, String]
  ): SyncIO[FunFixture[(CollectorRegistry, Client[IO])]] =
    ResourceFixture(buildMeteredClient(exemplar))
}
