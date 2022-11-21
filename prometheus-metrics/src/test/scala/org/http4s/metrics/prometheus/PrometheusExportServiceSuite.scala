package org.http4s.metrics.prometheus

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
import munit.CatsEffectSuite
import _root_.org.http4s._
import _root_.org.http4s.dsl.io._
import _root_.org.http4s.implicits._

class PrometheusClientMetricsSuite extends CatsEffectSuite {

  test("Returns Prometheus-format") {
    PrometheusExportService.build[IO].use { svc =>
      svc.routes.orNotFound
        .run(Request(method = GET, uri = Uri.unsafeFromString("/metrics")))
        .flatMap(resp => resp.body.compile.to(Array).map(new String(_)))
        .flatMap { resp =>
          IO {
            println(resp)
            val lines = resp.lines().toList
            // Assortment of lines that should appear in the output as a sanity check
            assert(clue(lines).contains("# TYPE jvm_memory_pool_allocated_bytes_total counter"))
            assert(clue(lines).contains("# HELP jvm_threads_daemon Daemon thread count of a JVM"))
          }
        }
    }
  }

}
