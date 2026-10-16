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
import org.http4s.client.dsl.io._
import org.http4s.dsl.io._
import org.http4s.implicits._

class PrometheusExportServiceSuite extends CatsEffectSuite {

  test("Returns Prometheus-format") {
    PrometheusExportService.build[IO].use { svc =>
      svc.routes.orNotFound
        .run(GET(uri"/metrics"))
        .flatMap(resp => resp.as[String])
        .flatMap { resp =>
          IO {
            val lines = resp.linesIterator.toList
            // Assortment of lines that should appear in the output as a sanity check
            assert(clue(lines).contains("# TYPE jvm_memory_pool_max_bytes gauge"))
            assert(clue(lines).contains("# HELP jvm_threads_daemon Daemon thread count of a JVM"))
            assert(clue(lines).contains("# HELP process_open_fds Number of open file descriptors."))
            assert(clue(lines).contains("# TYPE process_open_fds gauge"))
          }
        }
    }
  }

}
