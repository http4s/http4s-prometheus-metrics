# http4s-prometheus-metrics

```scala
libraryDependencies += "org.http4s" %% "http4s-prometheus-metrics" % "@VERSION@"
```

## Server example

```scala mdoc:reset:silent
import cats.effect._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.Router
import org.http4s.server.middleware.Metrics
import org.http4s.metrics.prometheus.{Prometheus, PrometheusExportService}

val apiService = HttpRoutes.of[IO] {
  case GET -> Root / "api" =>
    Ok()
}

val meteredRouter: Resource[IO, HttpRoutes[IO]] =
  for {
    metricsSvc <- PrometheusExportService.build[IO]
    metrics <- Prometheus.metricsOps[IO](metricsSvc.collectorRegistry, "server")
    router = Router[IO](
      "/api" -> Metrics[IO](metrics)(apiService),
      "/" -> metricsSvc.routes
    )
  } yield router
```

## Client example

```scala mdoc:reset:silent
import cats.effect._
import org.http4s._
import org.http4s.client._
import org.http4s.client.middleware.Metrics
import org.http4s.metrics.prometheus.Prometheus

val httpClient: Client[IO] = JavaNetClientBuilder[IO].create

val classifier = (r: Request[IO]) => Some(r.method.toString.toLowerCase)

val prefixedClient: Resource[IO, Client[IO]] =
  for {
    registry <- Prometheus.collectorRegistry[IO]
    metrics <- Prometheus.metricsOps[IO](registry, "prefix")
  } yield Metrics[IO](metrics, classifier)(httpClient)
```

## Exemplars

You can add Prometheus exemplars to most of the metrics (except gauges) recorded by `http4s-prometheus-metrics`
by using `Prometheus.metricsOpsWithExemplars` and passing an effect that captures the related exemplar labels.
