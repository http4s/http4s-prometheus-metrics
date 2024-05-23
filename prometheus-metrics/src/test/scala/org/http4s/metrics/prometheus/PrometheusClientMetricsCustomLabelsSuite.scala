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

import cats.effect.*
import io.prometheus.client.CollectorRegistry
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Request, Status}
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.client.middleware.Metrics
import org.http4s.dsl.io.*
import org.http4s.metrics.prometheus.util.*
import org.http4s.syntax.literals.*

import java.io.IOException
import java.util.concurrent.TimeoutException

class PrometheusClientMetricsCustomLabelsSuite extends CatsEffectSuite {
  val client: Client[IO] = Client.fromHttpApp[IO](HttpApp[IO](stub))

  meteredClient().test(
    "A http client with a prometheus metrics middleware should register a 2xx response"
  ) { case (registry, client) =>
    client.expect[String]("/ok").attempt.map { resp =>
      assertEquals(cntWithCustLbl(registry, "2xx_responses", "client")(custLblVals), 1.0)
      assertEquals(cntWithCustLbl(registry, "active_requests", "client")(custLblVals), 0.0)
      assertEquals(cntWithCustLbl(registry, "2xx_headers_duration", "client")(custLblVals), 0.05)
      assertEquals(cntWithCustLbl(registry, "2xx_total_duration", "client")(custLblVals), 0.1)
      assertEquals(resp, Right("200 OK"))
    }
  }

  meteredClient().test(
    "A http client with a prometheus metrics middleware should register a 4xx response"
  ) { case (registry, client) =>
    client.expect[String]("/bad-request").attempt.map { resp =>
      assertEquals(cntWithCustLbl(registry, "4xx_responses", "client")(custLblVals), 1.0)
      assertEquals(cntWithCustLbl(registry, "active_requests", "client")(custLblVals), 0.0)
      assertEquals(cntWithCustLbl(registry, "4xx_headers_duration", "client")(custLblVals), 0.05)
      assertEquals(cntWithCustLbl(registry, "4xx_total_duration", "client")(custLblVals), 0.1)
      resp match {
        case Left(UnexpectedStatus(status, _, _)) => assertEquals(status, Status.BadRequest)
        case other => fail(s"Unexpected response status: $other")
      }
    }
  }

  meteredClient().test(
    "A http client with a prometheus metrics middleware should register a 5xx response"
  ) { case (registry, client) =>
    client.expect[String]("/internal-server-error").attempt.map { resp =>
      assertEquals(cntWithCustLbl(registry, "5xx_responses", "client")(custLblVals), 1.0)
      assertEquals(cntWithCustLbl(registry, "active_requests", "client")(custLblVals), 0.0)
      assertEquals(cntWithCustLbl(registry, "5xx_headers_duration", "client")(custLblVals), 0.05)
      assertEquals(cntWithCustLbl(registry, "5xx_total_duration", "client")(custLblVals), 0.1)
      resp match {
        case Left(UnexpectedStatus(status, _, _)) =>
          assertEquals(status, Status.InternalServerError)
        case other => fail(s"Unexpected response status: $other")
      }
    }
  }

  meteredClient().test(
    "A http client with a prometheus metrics middleware should register a GET request"
  ) { case (registry, client) =>
    client.expect[String]("/ok").attempt.map { resp =>
      assertEquals(resp, Right("200 OK"))
      assertEquals(cntWithCustLbl(registry, "2xx_responses", "client", "get")(custLblVals), 1.0)
      assertEquals(cntWithCustLbl(registry, "active_requests", "client", "get")(custLblVals), 0.0)
      assertEquals(
        cntWithCustLbl(registry, "2xx_headers_duration", "client", "get")(custLblVals),
        0.05,
      )
      assertEquals(
        cntWithCustLbl(registry, "2xx_total_duration", "client", "get")(custLblVals),
        0.1,
      )
    }
  }

  meteredClient().test(
    "A http client with a prometheus metrics middleware should register a POST request"
  ) { case (registry, client) =>
    client.expect[String](Request[IO](POST, uri"/ok")).attempt.map { resp =>
      assertEquals(resp, Right("200 OK"))
      assertEquals(cntWithCustLbl(registry, "2xx_responses", "client", "post")(custLblVals), 1.0)
      assertEquals(cntWithCustLbl(registry, "active_requests", "client", "post")(custLblVals), 0.0)
      assertEquals(
        cntWithCustLbl(registry, "2xx_headers_duration", "client", "post")(custLblVals),
        0.05,
      )
      assertEquals(
        cntWithCustLbl(registry, "2xx_total_duration", "client", "post")(custLblVals),
        0.1,
      )
    }
  }

  meteredClient().test(
    "A http client with a prometheus metrics middleware should register a PUT request"
  ) { case (registry, client) =>
    client.expect[String](Request[IO](PUT, uri"/ok")).attempt.map { resp =>
      assertEquals(resp, Right("200 OK"))

      assertEquals(cntWithCustLbl(registry, "2xx_responses", "client", "put")(custLblVals), 1.0)
      assertEquals(cntWithCustLbl(registry, "active_requests", "client", "put")(custLblVals), 0.0)
      assertEquals(
        cntWithCustLbl(registry, "2xx_headers_duration", "client", "put")(custLblVals),
        0.05,
      )
      assertEquals(
        cntWithCustLbl(registry, "2xx_total_duration", "client", "put")(custLblVals),
        0.1,
      )
    }
  }

  meteredClient().test(
    "A http client with a prometheus metrics middleware should register a DELETE request"
  ) { case (registry, client) =>
    client.expect[String](Request[IO](DELETE, uri"/ok")).attempt.map { resp =>
      assertEquals(resp, Right("200 OK"))

      assertEquals(cntWithCustLbl(registry, "2xx_responses", "client", "delete")(custLblVals), 1.0)
      assertEquals(
        cntWithCustLbl(registry, "active_requests", "client", "delete")(custLblVals),
        0.0,
      )
      assertEquals(
        cntWithCustLbl(registry, "2xx_headers_duration", "client", "delete")(custLblVals),
        0.05,
      )
      assertEquals(
        cntWithCustLbl(registry, "2xx_total_duration", "client", "delete")(custLblVals),
        0.1,
      )
    }
  }

  meteredClient().test(
    "A http client with a prometheus metrics middleware should register an error"
  ) { case (registry, client) =>
    client.expect[String]("/error").attempt.map {
      case Left(_: IOException) =>
        assertEquals(
          cntWithCustLbl(registry, "errors", "client", cause = "java.io.IOException")(custLblVals),
          1.0,
        )
        assertEquals(cntWithCustLbl(registry, "active_requests", "client")(custLblVals), 0.0)
      case other => fail(s"Expected an IOException, got: $other")
    }
  }

  meteredClient().test(
    "A http client with a prometheus metrics middleware should register a timeout"
  ) { case (registry, client) =>
    client.expect[String]("/timeout").attempt.map {
      case Left(_: TimeoutException) =>
        assertEquals(cntWithCustLbl(registry, "timeouts", "client")(custLblVals), 1.0)
        assertEquals(cntWithCustLbl(registry, "active_requests", "client")(custLblVals), 0.0)
      case other => fail(s"Expected a TimeoutException, got: $other")
    }
  }

  private val classifier = (_: Request[IO]) => Some("classifier")

  meteredClient(classifier).test("use the provided request classifier") { case (registry, client) =>
    client.expect[String]("/ok").attempt.map { resp =>
      assertEquals(resp, Right("200 OK"))

      assertEquals(
        cntWithCustLbl(registry, "2xx_responses", "client", "get", "classifier")(custLblVals),
        1.0,
      )
      assertEquals(
        cntWithCustLbl(registry, "active_requests", "client", "get", "classifier")(custLblVals),
        0.0,
      )
      assertEquals(
        cntWithCustLbl(registry, "2xx_headers_duration", "client", "get", "classifier")(
          custLblVals
        ),
        0.05,
      )
      assertEquals(
        cntWithCustLbl(registry, "2xx_total_duration", "client", "get", "classifier")(custLblVals),
        0.1,
      )
    }
  }

  // This tests can't be easily done in munit-cats-effect as it wants to test after the Resource is freed
  meteredClient().test("unregister collectors".ignore) { case (cr, client) =>
    client.expect[String]("/ok").as(cr).map { registry =>
      assertEquals(cntWithCustLbl(registry, "2xx_responses", "client")(custLblVals), 0.0)
      assertEquals(cntWithCustLbl(registry, "active_requests", "client")(custLblVals), 0.0)
      assertEquals(cntWithCustLbl(registry, "2xx_headers_duration", "client")(custLblVals), 0.0)
      assertEquals(cntWithCustLbl(registry, "2xx_total_duration", "client")(custLblVals), 0.0)
    }
  }

  private def buildMeteredClient(
      classifier: Request[IO] => Option[String]
  ): Resource[IO, (CollectorRegistry, Client[IO])] = {
    implicit val clock: Clock[IO] = FakeClock[IO]

    for {
      registry <- Prometheus.collectorRegistry[IO]
      metrics <- Prometheus
        .metricsOpsWithCustomLabels[IO](registry, "client", customLabelsAndValues = custLblVals)
    } yield (registry, Metrics(metrics, classifier)(client))
  }

  def meteredClient(
      classifier: Request[IO] => Option[String] = (_: Request[IO]) => None
  ): SyncIO[FunFixture[(CollectorRegistry, Client[IO])]] =
    ResourceFixture(buildMeteredClient(classifier))
}
