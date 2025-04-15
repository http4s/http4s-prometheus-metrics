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
import io.prometheus.metrics.model.registry.PrometheusRegistry
import munit.CatsEffectSuite
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Method.GET
import org.http4s.Request
import org.http4s.Status
import org.http4s.dsl.io.*
import org.http4s.metrics.prometheus.util.*
import org.http4s.server.middleware.Metrics
import org.http4s.syntax.all.*

class PrometheusServerMetricsCustomLabelsSuite extends CatsEffectSuite {

  private val testRoutes = HttpRoutes.of[IO](stub)

  // "A http routes with a prometheus metrics middleware" should {
  meteredRoutes().test(
    "A http routes with a prometheus metrics middleware should register a 2xx response"
  ) { case (registry, routes) =>
    val req = Request[IO](uri = uri"/ok")

    val resp = routes.run(req)
    resp.flatMap { r =>
      r.as[String].map { b =>
        assertEquals(b, "200 OK")
        assertEquals(r.status, Status.Ok)
        assertEquals(cntWithCustLbl(registry, "2xx_responses", "server")(paypalProviderLabels), 1.0)
        assertEquals(
          cntWithCustLbl(registry, "active_requests", "server")(paypalProviderLabels),
          0.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_headers_duration", "server")(paypalProviderLabels),
          0.05,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_total_duration", "server")(paypalProviderLabels),
          0.1,
        )
      }
    }
  }

  meteredRoutes().test(
    "A http routes with a prometheus metrics middleware should register a 4xx response"
  ) { case (registry, routes) =>
    val req = Request[IO](uri = uri"/bad-request")

    routes.run(req).flatMap { r =>
      r.as[String].map { b =>
        assertEquals(r.status, Status.BadRequest)
        assertEquals(b, "400 Bad Request")

        assertEquals(cntWithCustLbl(registry, "4xx_responses", "server")(paypalProviderLabels), 1.0)
        assertEquals(
          cntWithCustLbl(registry, "active_requests", "server")(paypalProviderLabels),
          0.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "4xx_headers_duration", "server")(paypalProviderLabels),
          0.05,
        )
        assertEquals(
          cntWithCustLbl(registry, "4xx_total_duration", "server")(paypalProviderLabels),
          0.1,
        )
      }
    }
  }

  meteredRoutes().test(
    "A http routes with a prometheus metrics middleware should register a 5xx response"
  ) { case (registry, routes) =>
    val req = Request[IO](uri = uri"/internal-server-error")

    routes.run(req).flatMap { r =>
      r.as[String].map { b =>
        assertEquals(r.status, Status.InternalServerError)
        assertEquals(b, "500 Internal Server Error")

        assertEquals(cntWithCustLbl(registry, "5xx_responses", "server")(paypalProviderLabels), 1.0)
        assertEquals(
          cntWithCustLbl(registry, "active_requests", "server")(paypalProviderLabels),
          0.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "5xx_headers_duration", "server")(paypalProviderLabels),
          0.05,
        )
        assertEquals(
          cntWithCustLbl(registry, "5xx_total_duration", "server")(paypalProviderLabels),
          0.1,
        )
      }
    }
  }

  meteredRoutes().test(
    "A http routes with a prometheus metrics middleware should register a GET request"
  ) { case (registry, routes) =>
    val req = Request[IO](method = GET, uri = uri"/ok")

    routes.run(req).flatMap { r =>
      r.as[String].map { b =>
        assertEquals(r.status, Status.Ok)
        assertEquals(b, "200 OK")

        assertEquals(
          cntWithCustLbl(registry, "2xx_responses", "server", "get")(paypalProviderLabels),
          1.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "active_requests", "server", "get")(paypalProviderLabels),
          0.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_headers_duration", "server", "get")(paypalProviderLabels),
          0.05,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_total_duration", "server", "get")(paypalProviderLabels),
          0.1,
        )
      }
    }
  }

  meteredRoutes().test(
    "A http routes with a prometheus metrics middleware should register a POST request"
  ) { case (registry, routes) =>
    val req = Request[IO](method = POST, uri = uri"/ok")

    routes.run(req).flatMap { r =>
      r.as[String].map { b =>
        assertEquals(r.status, Status.Ok)
        assertEquals(b, "200 OK")

        assertEquals(
          cntWithCustLbl(registry, "2xx_responses", "server", "post")(paypalProviderLabels),
          1.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "active_requests", "server", "post")(paypalProviderLabels),
          0.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_headers_duration", "server", "post")(paypalProviderLabels),
          0.05,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_total_duration", "server", "post")(paypalProviderLabels),
          0.1,
        )
      }
    }
  }

  meteredRoutes().test(
    "A http routes with a prometheus metrics middleware should register a PUT request"
  ) { case (registry, routes) =>
    val req = Request[IO](method = PUT, uri = uri"/ok")

    routes.run(req).flatMap { r =>
      r.as[String].map { b =>
        assertEquals(r.status, Status.Ok)
        assertEquals(b, "200 OK")

        assertEquals(
          cntWithCustLbl(registry, "2xx_responses", "server", "put")(paypalProviderLabels),
          1.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "active_requests", "server", "put")(paypalProviderLabels),
          0.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_headers_duration", "server", "put")(paypalProviderLabels),
          0.05,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_total_duration", "server", "put")(paypalProviderLabels),
          0.1,
        )
      }
    }
  }

  meteredRoutes().test(
    "A http routes with a prometheus metrics middleware should register a DELETE request"
  ) { case (registry, routes) =>
    val req = Request[IO](method = DELETE, uri = uri"/ok")

    routes.run(req).flatMap { r =>
      r.as[String].map { b =>
        assertEquals(r.status, Status.Ok)
        assertEquals(b, "200 OK")

        assertEquals(
          cntWithCustLbl(registry, "2xx_responses", "server", "delete")(paypalProviderLabels),
          1.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "active_requests", "server", "delete")(paypalProviderLabels),
          0.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_headers_duration", "server", "delete")(
            paypalProviderLabels
          ),
          0.05,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_total_duration", "server", "delete")(paypalProviderLabels),
          0.1,
        )
      }
    }
  }

  meteredRoutes().test(
    "A http routes with a prometheus metrics middleware should register an error"
  ) { case (registry, routes) =>
    val req = Request[IO](method = GET, uri = uri"/error")

    routes.run(req).attempt.map { r =>
      assert(r.isLeft)

      assertEquals(
        cntWithCustLbl(registry, "errors", "server", cause = "java.io.IOException")(
          paypalProviderLabels
        ),
        1.0,
      )
      assertEquals(cntWithCustLbl(registry, "active_requests", "server")(paypalProviderLabels), 0.0)
      assertEquals(
        cntWithCustLbl(registry, "5xx_headers_duration", "server")(paypalProviderLabels),
        0.05,
      )
      assertEquals(
        cntWithCustLbl(registry, "5xx_total_duration", "server")(paypalProviderLabels),
        0.05,
      )
    }
  }

  meteredRoutes().test(
    "A http routes with a prometheus metrics middleware should register an abnormal termination"
  ) { case (registry, routes) =>
    val req = Request[IO](method = GET, uri = uri"/abnormal-termination")

    routes.run(req).flatMap { r =>
      r.body.attempt.compile.lastOrError.map { b =>
        assertEquals(r.status, Status.Ok)
        assert(b.isLeft)

        assertEquals(
          cntWithCustLbl(
            registry,
            "abnormal_terminations",
            "server",
            cause = "java.lang.RuntimeException",
          )(paypalProviderLabels),
          1.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "active_requests", "server")(paypalProviderLabels),
          0.0,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_headers_duration", "server")(paypalProviderLabels),
          0.05,
        )
        assertEquals(
          cntWithCustLbl(registry, "2xx_total_duration", "server")(paypalProviderLabels),
          0.1,
        )
      }
    }
  }

  private val classifierFunc = (_: Request[IO]) => Some("classifier")

  meteredRoutes(classifierFunc).test("use the provided request classifier") {
    case (registry, routes) =>
      val req = Request[IO](uri = uri"/ok")

      routes.run(req).flatMap { r =>
        r.as[String].map { b =>
          assertEquals(r.status, Status.Ok)
          assertEquals(b, "200 OK")

          assertEquals(
            cntWithCustLbl(registry, "2xx_responses", "server", "get", "classifier")(
              paypalProviderLabels
            ),
            1.0,
          )
          assertEquals(
            cntWithCustLbl(registry, "active_requests", "server", "get", "classifier")(
              paypalProviderLabels
            ),
            0.0,
          )
          assertEquals(
            cntWithCustLbl(registry, "2xx_headers_duration", "server", "get", "classifier")(
              paypalProviderLabels
            ),
            0.05,
          )
          assertEquals(
            cntWithCustLbl(registry, "2xx_total_duration", "server", "get", "classifier")(
              paypalProviderLabels
            ),
            0.1,
          )
        }
      }
  }

  // This tests can't be easily done in munit-cats-effect as it wants to test after the Resource is freed
  meteredRoutes().test("unregister collectors".ignore) { case (cr, routes) =>
    val req = Request[IO](uri = uri"/ok")

    routes.run(req).as(cr).map { registry =>
      assertEquals(cntWithCustLbl(registry, "2xx_responses", "server")(paypalProviderLabels), 0.0)
      assertEquals(cntWithCustLbl(registry, "active_requests", "server")(paypalProviderLabels), 0.0)
      assertEquals(
        cntWithCustLbl(registry, "2xx_headers_duration", "server")(paypalProviderLabels),
        0.0,
      )
      assertEquals(
        cntWithCustLbl(registry, "2xx_total_duration", "server")(paypalProviderLabels),
        0.0,
      )
    }
  }

  def buildMeteredRoutes(
      classifier: Request[IO] => Option[String] = (_: Request[IO]) => None
  ): Resource[IO, (PrometheusRegistry, HttpApp[IO])] = {
    implicit val clock: Clock[IO] = FakeClock[IO]
    for {
      registry <- Prometheus.prometheusRegistry[IO]
      metrics <- Prometheus
        .default[IO](registry)
        .withPrefix("server")
        .buildCustomMetricsOps(providerCustomLabels)
    } yield (
      registry,
      Metrics
        .withCustomLabels(metrics, paypalLabelValues, classifierF = classifier)(testRoutes)
        .orNotFound,
    )
  }

  def meteredRoutes(
      classifier: Request[IO] => Option[String] = (_: Request[IO]) => None
  ): SyncIO[FunFixture[(PrometheusRegistry, HttpApp[IO])]] =
    ResourceFunFixture(buildMeteredRoutes(classifier))
}
