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

package quasar.api.datasource

import slamdata.Predef._
import quasar.{Condition, ConditionMatchers, Qspec}
import quasar.api.ResourcePath

import scala.Predef.assert
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.UUID

import cats.effect.{Effect, IO}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.auto._
import fs2.async.Promise
import org.specs2.execute.AsResult
import org.specs2.matcher.Matcher
import org.specs2.specification.core.Fragment
import scalaz.{\/, Equal, Foldable, IMap, Order, Show}
import scalaz.syntax.equal._
import scalaz.syntax.foldable._

abstract class DatasourcesSpec[F[_], G[_]: Foldable, I: Order: Show, C: Equal: Show](
    implicit F: Effect[F])
    extends Qspec
    with ConditionMatchers {

  import DatasourceError._

  def datasources: Datasources[F, G, I, C]

  def supportedType: DatasourceType

  // Must be distinct.
  def validConfigs: (C, C)

  assert(validConfigs._1 =/= validConfigs._2, "validConfigs must be distinct!")

  def mutationExamples(f: DatasourceRef[C] => F[Err \/ I]) = {
    "lookup on success" >>* {
      for {
        a <- refA

        fr <- f(a)

        i <- expectSuccess(fr)

        r <- datasources.datasourceRef(i)
      } yield {
        r must beRef(a)
      }
    }

    "normal status on success" >>* {
      for {
        a <- refA

        fr <- f(a)

        i <- expectSuccess(fr)

        r <- datasources.datasourceStatus(i)
      } yield {
        r must be_\/-(beNormal[Exception])
      }
    }

    "error when name exists" >>* {
      for {
        n <- randomName

        x = DatasourceRef(supportedType, n, validConfigs._1)
        y = DatasourceRef(supportedType, n, validConfigs._2)

        r <- f(x) *> f(y)
      } yield {
        r must be_-\/(equal[Err](DatasourceNameExists(n)))
      }
    }

    "error when type not supported" >>* {
      val unsup = DatasourceType("--unsupported--", 17L)

      for {
        n <- randomName

        uref = DatasourceRef(unsup, n, validConfigs._1)

        u <- datasources.addDatasource(uref)

        types <- datasources.supportedDatasourceTypes
      } yield {
        u must be_-\/(equal[Err](DatasourceUnsupported(unsup, types)))
      }
    }
  }

  def resultsInNotFound[E >: ExistentialError[I] <: Err, A](f: I => F[E \/ A]) =
    for {
      i <- refA >>= createRef

      c <- datasources.removeDatasource(i)

      fr <- f(i)
    } yield {
      c must beNormal
      fr must beNotFound[E](i)
    }

  def discoveryExamples[E >: ExistentialError[I] <: Err, A](f: (I, ResourcePath) => F[E \/ A]) = {

    "error when datasource not found" >>* {
      resultsInNotFound(f(_, ResourcePath.root()))
    }

    "respond when datasource exists" >>* {
      for {
        i <- refA >>= createRef

        fr <- f(i, ResourcePath.root())
      } yield {
        fr must not(beNotFound[E](i))
      }
    }
  }

  "add datasource" >> mutationExamples { ref =>
    datasources
      .addDatasource(ref)
      .map(_.leftMap[DatasourceError[I, C]](e => e))
  }

  "replace datasource" >> {
    mutationExamples(ref => for {
      b <- refB

      i <- createRef(b)

      c <- datasources.replaceDatasource(i, ref)
    } yield {
      Condition.disjunctionIso
        .get(c.map[DatasourceError[I, C]](e => e))
        .map(_ => i)
    })

    "fails when datasource not found" >>* resultsInNotFound { i =>
      for {
        b <- refB
        c <- datasources.replaceDatasource(i, b)
      } yield {
        Condition.disjunctionIso
          .get(c.map[DatasourceError[I, C]](e => e))
      }
    }
  }

  "all metadata" >> {
    "returns metadata for all datasources" >>* {
      for {
        a <- refA
        ia <- createRef(a)

        b <- refB
        ib <- createRef(b)

        g <- datasources.allDatasourceMetadata

        m = IMap.fromFoldable(g)
      } yield {
        m.lookup(ia).exists(v => v.kind ≟ a.kind && v.name ≟ a.name) must beTrue
        m.lookup(ib).exists(v => v.kind ≟ b.kind && v.name ≟ b.name) must beTrue
      }
    }
  }

  "lookup ref" >> {
    "error when not found" >>* {
      resultsInNotFound(datasources.datasourceRef)
    }
  }

  "lookup status" >> {
    "error when not found" >>* {
      resultsInNotFound(datasources.datasourceStatus)
    }
  }

  "path is resource" >> {
    discoveryExamples(datasources.pathIsResource)
  }

  "prefixed child paths" >> {
    discoveryExamples(datasources.prefixedChildPaths)
  }

  "remove datasource" >> {
    "error when not found" >>* {
      resultsInNotFound { id =>
        datasources.removeDatasource(id)
          .map(Condition.disjunctionIso.get(_))
      }
    }

    "not in metadata on success" >>* {
      for {
        a <- refA

        i <- createRef(a)

        metaBefore <- datasources.allDatasourceMetadata

        _ <- datasources.removeDatasource(i)

        metaAfter <- datasources.allDatasourceMetadata
      } yield {
        metaBefore.any(_._1 ≟ i) must beTrue
        metaAfter.any(_._1 ≟ i) must beFalse
      }
    }
  }

  ////

  implicit class RunExample(s: String) {
    def >>*[A: AsResult](fa: => F[A]): Fragment = {
      val run = for {
        p <- Promise.empty[IO, Either[Throwable, A]]
        _ <- F.runAsync(fa)(p.complete(_))
        r <- p.get
        a <- IO.fromEither(r)
      } yield a

      s >> run.unsafeRunSync
    }
  }

  type Err = DatasourceError[I, C]

  // To allow control over conflicts.
  val randomName: F[DatasourceName] =
    F.delay(DatasourceName("ds-" + UUID.randomUUID()))

  def refA: F[DatasourceRef[C]] =
    randomName map (DatasourceRef(supportedType, _, validConfigs._1))

  def refB: F[DatasourceRef[C]] =
    randomName map (DatasourceRef(supportedType, _, validConfigs._2))

  def createRef(r: DatasourceRef[C]): F[I] =
    datasources.addDatasource(r) >>= expectSuccess

  def expectSuccess[E <: Err, A](r: E \/ A): F[A] =
    r.fold(
      e => F.raiseError(new RuntimeException(s"Operation failed: ${Show[Err].shows(e)}")),
      F.pure(_))

  def beRef(ref: DatasourceRef[C]): Matcher[ExistentialError[I] \/ DatasourceRef[C]] =
    be_\/-(equal(ref))

  def beNotFound[E >: ExistentialError[I] <: Err](id: I): Matcher[E \/ _] =
    be_-\/[E](equal[Err](DatasourceNotFound(id)) ^^ { (e: E) => e: Err })
}
