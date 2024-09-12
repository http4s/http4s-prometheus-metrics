# http4s-prometheus-metrics

Provides [Prometheus](https://prometheus.io/) integration for http4s.

## SBT coordinates

```scala
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-prometheus-metrics" % http4sPrometheus-MetricsV
)
```

## Compatibility

| http4s-prometheus-metrics | http4s-core | prometheus-metrics | Scala 2.12 | Scala 2.13 | Scala 3 | Status |
|:--------------------------|:------------|:-------------------|------------|------------|---------|:-------|
| 0.23.x                    | 0.23.x      | 0.11.x             | ✅         | ✅         | ❌      | EOL    |
| 0.24.x                    | 0.23.x      | 0.16.x             | ✅         | ✅         | ✅      | EOL    |
| 0.25.x                    | 0.23.x      | 0.16.x             | ✅         | ✅         | ✅      | Stable |

[prometheus-metrics]: https://com-lihaoyi.github.io/prometheus-metrics/
