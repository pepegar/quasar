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

package quasar.impl

import quasar._
import quasar.common.PhaseResultTell
import quasar.contrib.iota._
import quasar.contrib.matryoshka.safe
import quasar.qscript._

import matryoshka.{Hole => _, _}

import cats.Monad
import cats.syntax.applicative._

final class RegressionQScriptEvaluator[
    T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT,
    F[_]: Monad: PhaseResultTell]
    extends CountingQScriptEvaluator[T, F] {

  def optimize(norm: T[QScriptNormalized[T, ?]]): F[T[QSM]] =
    safe.transCata[T[QScriptNormalized[T, ?]], QScriptNormalized[T, ?], T[QSM], QSM](norm)(
      QSNormToQSM.inject(_)).pure[F]
}
