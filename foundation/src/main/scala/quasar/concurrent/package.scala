/*
 * Copyright 2020 Precog Data
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

package quasar

import slamdata.Predef.String

import java.util.concurrent.Executors

import cats.effect.{Blocker, Resource, Sync}

package object concurrent {
  implicit class BlockerCompanionOps(blocker: Blocker.type) {
    def cached[F[_]: Sync](name: String): Resource[F, Blocker] =
      blocker.fromExecutorService[F](Sync[F] delay {
        Executors.newCachedThreadPool(NamedDaemonThreadFactory(name))
      })
  }
}
