/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.api

import slamdata.Predef._
import quasar.pkg.tests._

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.{Positive => RPositive}
import eu.timepit.refined.scalacheck.numeric._
import org.scalacheck.Gen

trait DataSourceTypeGenerator {
  implicit val dataSourceTypeArbitrary: Arbitrary[DataSourceType] =
    Arbitrary(for {
      name <- genName
      ver  <- chooseRefinedNum[Refined, Long, RPositive](1L, 100L)
    } yield DataSourceType(name, ver))

  private def genName: Gen[DataSourceType.Name] =
    Gen.listOf(
      Gen.frequency(
        100 -> Gen.alphaNumChar,
        3   -> Gen.const('-')))
      .map(cs => Refined.unsafeApply[String, DataSourceType.NameP](cs.mkString))
}

object DataSourceTypeGenerator extends DataSourceTypeGenerator
