package slamdata.engine.physical.mongodb

import slamdata.engine._
import slamdata.engine.fp._
import slamdata.engine.analysis.fixplate._
import slamdata.engine.analysis._
import slamdata.engine.sql.SQLParser
import slamdata.engine.std._

import scalaz._

import collection.immutable.ListMap

import org.specs2.mutable._
import org.specs2.matcher.{Matcher, Expectable}

class PlannerSpec extends Specification with CompilerHelpers {
  import StdLib._
  import structural._
  import math._
  import LogicalPlan._
  import SemanticAnalysis._
  import WorkflowTask._
  import PipelineOp._
  import ExprOp._

  case class equalToWorkflow(expected: Workflow) extends Matcher[Workflow] {
    def apply[S <: Workflow](s: Expectable[S]) = {
      def diff(l: S, r: Workflow): String = {
        val lt = RenderTree[Workflow].render(l)
        val rt = RenderTree[Workflow].render(r)
        RenderTree.show(lt diff rt)(new RenderTree[RenderedTree] { override def render(v: RenderedTree) = v }).toString
      }
      result(expected == s.value,
             "\ntrees are equal:\n" + diff(s.value, expected),
             "\ntrees are not equal:\n" + diff(s.value, expected),
             s)
    }
  }

  def plan(query: String): Either[Error, Workflow] = {
    (for {
      logical <- compile(query).leftMap(e => PlannerError.InternalError("query could not be compiled: " + e))
      simplified <- \/-(Optimizer.simplify(logical))
      phys <- MongoDbPlanner.plan(simplified)
    } yield phys).toEither
  }

  def plan(logical: Term[LogicalPlan]): Either[Error, Workflow] =
    (for {
      simplified <- \/-(Optimizer.simplify(logical))
      phys <- MongoDbPlanner.plan(simplified)
    } yield phys).toEither

  def beWorkflow(task: WorkflowTask) = beRight(equalToWorkflow(Workflow(task)))

  "plan from query string" should {
    "plan simple select *" in {
      plan("select * from foo") must beWorkflow(ReadTask(Collection("foo")))
    }

    "plan count(*)" in {
      plan("select count(*) from foo") must beWorkflow( 
        PipelineTask(
          ReadTask(Collection("foo")),
          Pipeline(List(
            Group(
              Grouped(ListMap(BsonField.Name("0") -> Count)),
              -\/(Literal(Bson.Int32(1))))))))
    }

    "plan simple field projection on single set" in {
      plan("select foo.bar from foo") must
        beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(BsonField.Name("bar") -> -\/(DocField(BsonField.Name("bar"))))))
            ))
          )
        )
    }

    "plan simple field projection on single set when table name is inferred" in {
      plan("select bar from foo") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(BsonField.Name("bar") -> -\/(DocField(BsonField.Name("bar"))))))
            ))
          )
        )
    }
    
    "plan multiple field projection on single set when table name is inferred" in {
      plan("select bar, baz from foo") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("bar") -> -\/(DocField(BsonField.Name("bar"))),
                BsonField.Name("baz") -> -\/(DocField(BsonField.Name("baz")))
              )))
            ))
          )
        )
    }

    "plan simple addition on two fields" in {
      plan("select foo + bar from baz") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("baz")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(BsonField.Name("0") -> -\/ (ExprOp.Add(DocField(BsonField.Name("foo")), DocField(BsonField.Name("bar")))))))
            ))
          )
        )
    }
    
    "plan concat" in {
      plan("select concat(bar, baz) from foo") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("0") -> -\/ (ExprOp.Concat(
                  DocField(BsonField.Name("bar")),
                  DocField(BsonField.Name("baz")),
                  Nil
                ))
              )))
            ))
          )
        )
    }

    "plan lower" in {
      plan("select lower(bar) from foo") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("0") ->
                  -\/(ExprOp.ToLower(DocField(BsonField.Name("bar")))))))))))
    }

    "plan coalesce" in {
      plan("select coalesce(bar, baz) from foo") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("0") ->
                  -\/(ExprOp.IfNull(
                    DocField(BsonField.Name("bar")),
                    DocField(BsonField.Name("baz")))))))))))
    }

    "plan date field extraction" in {
      plan("select date_part('day', baz) from foo") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("0") ->
                  -\/(ExprOp.DayOfMonth(DocField(BsonField.Name("baz")))))))))))
    }

    "plan complex date field extraction" in {
      plan("select date_part('quarter', baz) from foo") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("0") ->
                  -\/(
                    ExprOp.Add(
                      ExprOp.Divide(
                        ExprOp.DayOfYear(DocField(BsonField.Name("baz"))),
                        ExprOp.Literal(Bson.Int32(92))),
                      ExprOp.Literal(Bson.Int32(1)))))))))))
    }

    "plan array length" in {
      plan("select array_length(bar, 1) from foo") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("0") ->
                  -\/(ExprOp.Size(DocField(BsonField.Name("bar")))))))))))
    }

    "plan conditional" in {
      plan("select case when pop < 10000 then city else loc end from zips") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("zips")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("0") ->
                  -\/(Cond(
                    Lt(
                      DocField(BsonField.Name("pop")),
                      ExprOp.Literal(Bson.Int64(10000))),
                    DocField(BsonField.Name("city")),
                    DocField(BsonField.Name("loc")))))))))))
    }
    
    "plan simple filter" in {
      plan("select * from foo where bar > 10") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Match(Selector.Doc(BsonField.Name("bar") -> Selector.Gt(Bson.Int64(10))))
            ))
          )
        )
    }
    
    "plan simple filter with expression in projection" in {
      plan("select a + b from foo where bar > 10") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Match(Selector.Doc(BsonField.Name("bar") -> Selector.Gt(Bson.Int64(10)))),
              Project(Reshape.Doc(ListMap(BsonField.Name("0") -> -\/ (ExprOp.Add(
                                                                    DocField(BsonField.Name("a")), 
                                                                    DocField(BsonField.Name("b"))
                                                                  ))
              )))
            ))
          )
        )
    }
    
    "plan filter with between" in {
      plan("select * from foo where bar between 10 and 100") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Match(
                Selector.And(
                  Selector.Doc(BsonField.Name("bar") -> Selector.Gte(Bson.Int64(10))),
                  Selector.Doc(BsonField.Name("bar") -> Selector.Lte(Bson.Int64(100)))
                )
              )
            ))
          )
        )
    }
    
    "plan filter with like" in {
      plan("select * from foo where bar like 'A%'") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Match(
                Selector.Doc(BsonField.Name("bar") -> Selector.Regex("^A.*$", false, false, false, false))
              )
            ))
          )
        )
    }
    
    "plan complex filter" in {
      plan("select * from foo where bar > 10 and (baz = 'quux' or foop = 'zebra')") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Match(Selector.And(
                Selector.Doc(BsonField.Name("bar") -> Selector.Gt(Bson.Int64(10))),
                Selector.Or(
                  Selector.Doc(BsonField.Name("baz") -> Selector.Eq(Bson.Text("quux"))),
                  Selector.Doc(BsonField.Name("foop") -> Selector.Eq(Bson.Text("zebra")))
                )
              ))
            ))
          )
        )
    }

    "plan simple sort with field in projection" in {
      plan("select bar from foo order by bar") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("lEft") -> \/- (Reshape.Arr(ListMap(
                                                BsonField.Index(0) -> \/- (Reshape.Doc(ListMap(
                                                                            BsonField.Name("key") -> -\/ (ExprOp.DocField(BsonField.Name("bar"))), 
                                                                            BsonField.Name("order") -> -\/(ExprOp.Literal(Bson.Text("ASC"))))))
                                              ))), 
                BsonField.Name("rIght") -> \/-  (Reshape.Doc(ListMap(
                                                  BsonField.Name("rIght") -> \/-  (Reshape.Doc(ListMap(
                                                                                    BsonField.Name("bar") -> -\/ (ExprOp.DocField(BsonField.Name("bar")))
                                                                                  )))
                                                )))
              ))), 
              Sort(NonEmptyList(BsonField.Name("lEft") \ BsonField.Index(0) \ BsonField.Name("key") -> Ascending)),
              Project(Reshape.Doc(ListMap(
                BsonField.Name("bar") -> -\/ (ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("bar")))
              )))
            ))
          )
        )
    }
    
    "plan simple sort with wildcard" in {
      plan("select * from foo order by bar") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Sort(NonEmptyList(BsonField.Name("bar") -> Ascending))
            ))
          )
        )
    }.pendingUntilFixed

    "plan sort with expression in key" in {
      plan("select baz from foo order by bar/10") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("lEft") -> \/- (Reshape.Arr(ListMap(
                                                BsonField.Index(0) -> \/- (Reshape.Doc(ListMap(
                                                                            BsonField.Name("key") -> -\/(ExprOp.Divide(ExprOp.DocField(BsonField.Name("bar")), ExprOp.Literal(Bson.Int64(10)))),
                                                                            BsonField.Name("order") -> -\/(ExprOp.Literal(Bson.Text("ASC"))))))))), 
                BsonField.Name("rIght") -> \/-  (Reshape.Doc(ListMap(
                                                  BsonField.Name("rIght") -> \/-  (Reshape.Doc(ListMap(
                                                                                    BsonField.Name("baz") -> -\/(ExprOp.DocField(BsonField.Name("baz"))), 
                                                                                    BsonField.Name("__sd__0") -> -\/(ExprOp.Divide(ExprOp.DocField(BsonField.Name("bar")), ExprOp.Literal(Bson.Int64(10))))))))))))), 
              Sort(NonEmptyList(BsonField.Name("lEft") \ BsonField.Index(0) \ BsonField.Name("key") -> Ascending)), 
              Project(Reshape.Doc(ListMap(BsonField.Name("baz") -> -\/(ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("baz")))))))))
        )
    }

    "plan sort with wildcard and expression in key" in {
      plan("select * from foo order by bar/10") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            ???  // TODO: Currently cannot deal with logical plan having ObjectConcat with collection as an arg
          )
        )
    }.pendingUntilFixed
    
    "plan simple sort with field not in projections" in {
      plan("select name from person order by height") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("person")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(BsonField.Name("lEft") -> \/-(Reshape.Arr(ListMap(BsonField.Index(0) -> \/-(Reshape.Doc(ListMap(BsonField.Name("key") -> -\/(ExprOp.DocField(BsonField.Name("height"))), BsonField.Name("order") -> -\/(ExprOp.Literal(Bson.Text("ASC"))))))))), BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("name") -> -\/(ExprOp.DocField(BsonField.Name("name"))), BsonField.Name("__sd__0") -> -\/(ExprOp.DocField(BsonField.Name("height")))))))))))), 
              Sort(NonEmptyList(BsonField.Name("lEft") \ BsonField.Index(0) \ BsonField.Name("key") -> Ascending)), 
              Project(Reshape.Doc(ListMap(BsonField.Name("name") -> -\/(ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("name")))))))))
        )
    }
    
    "plan sort with expression and alias" in {
      plan("select pop/1000 as popInK from zips order by popInK") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("zips")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(BsonField.Name("lEft") -> \/-(Reshape.Arr(ListMap(BsonField.Index(0) -> \/-(Reshape.Doc(ListMap(BsonField.Name("key") -> -\/(ExprOp.Divide(ExprOp.DocField(BsonField.Name("pop")), ExprOp.Literal(Bson.Int64(1000)))), BsonField.Name("order") -> -\/(ExprOp.Literal(Bson.Text("ASC"))))))))), BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("popInK") -> -\/(ExprOp.Divide(ExprOp.DocField(BsonField.Name("pop")), ExprOp.Literal(Bson.Int64(1000))))))))))))), 
              Sort(NonEmptyList(BsonField.Name("lEft") \ BsonField.Index(0) \ BsonField.Name("key") -> Ascending)), 
              Project(Reshape.Doc(ListMap(BsonField.Name("popInK") -> -\/(ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("popInK")))))))))
        )
    }
    
    "plan sort with filter" in {
      plan("select city, pop from zips where pop <= 1000 order by pop desc, city") must
        beWorkflow(
          PipelineTask(
            ReadTask(Collection("zips")),
            Pipeline(List(
              Match(Selector.Doc(BsonField.Name("pop") -> Selector.Lte(Bson.Int64(1000)))), 
              Project(Reshape.Doc(ListMap(
                BsonField.Name("lEft") -> \/- (Reshape.Arr(ListMap(
                                                BsonField.Index(0) -> \/- (Reshape.Doc(ListMap(
                                                                            BsonField.Name("key") -> -\/ (ExprOp.DocField(BsonField.Name("pop"))), 
                                                                            BsonField.Name("order") -> -\/(ExprOp.Literal(Bson.Text("DESC")))))), 
                                                BsonField.Index(1) -> \/- (Reshape.Doc(ListMap(
                                                                            BsonField.Name("key") -> -\/ (ExprOp.DocField(BsonField.Name("city"))), 
                                                                            BsonField.Name("order") -> -\/ (ExprOp.Literal(Bson.Text("ASC"))))))))), 
                BsonField.Name("rIght") -> \/-  (Reshape.Doc(ListMap(
                                                  BsonField.Name("rIght") -> \/-  (Reshape.Doc(ListMap(
                                                                                    BsonField.Name("city") -> -\/(ExprOp.DocField(BsonField.Name("city"))), 
                                                                                    BsonField.Name("pop") -> -\/(ExprOp.DocField(BsonField.Name("pop")))))))))))), 
              Sort(NonEmptyList(
                BsonField.Name("lEft") \ BsonField.Index(0) \ BsonField.Name("key") -> Descending, 
                BsonField.Name("lEft") \ BsonField.Index(1) \ BsonField.Name("key") -> Ascending)), 
              Project(Reshape.Doc(ListMap(
                BsonField.Name("city") -> -\/ (ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("city"))), 
                BsonField.Name("pop") -> -\/ (ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("pop")))))))))
        )
    }
    
    "plan sort with expression, alias, and filter" in {
      plan("select pop/1000 as popInK from zips where pop >= 1000 order by popInK") must
        beWorkflow(
          PipelineTask(
            ReadTask(Collection("zips")),
            Pipeline(List(
              Match(Selector.Doc(BsonField.Name("pop") -> Selector.Gte(Bson.Int64(1000)))), 
              Project(Reshape.Doc(ListMap(BsonField.Name("lEft") -> \/-(Reshape.Arr(ListMap(BsonField.Index(0) -> \/-(Reshape.Doc(ListMap(BsonField.Name("key") -> -\/(ExprOp.Divide(ExprOp.DocField(BsonField.Name("pop")), ExprOp.Literal(Bson.Int64(1000)))), BsonField.Name("order") -> -\/(ExprOp.Literal(Bson.Text("ASC"))))))))), BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("popInK") -> -\/(ExprOp.Divide(ExprOp.DocField(BsonField.Name("pop")), ExprOp.Literal(Bson.Int64(1000))))))))))))),
              Sort(NonEmptyList(BsonField.Name("lEft") \ BsonField.Index(0) \ BsonField.Name("key") -> Ascending)),
              Project(Reshape.Doc(ListMap(BsonField.Name("popInK") -> -\/(ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("popInK")))))))))  
        )
    }

    "plan multiple column sort with wildcard" in {
      plan("select * from foo order by bar, baz desc") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Sort(NonEmptyList(BsonField.Name("bar") -> Ascending, 
                                BsonField.Name("baz") -> Descending
              ))
            ))
          )
        )
    }.pendingUntilFixed
    
    "plan many sort columns" in {
      plan("select * from foo order by a1, a2, a3, a4, a5, a6") must
       beWorkflow(
          PipelineTask(
            ReadTask(Collection("foo")),
            Pipeline(List(
              Sort(NonEmptyList(BsonField.Name("a1") -> Ascending, 
                                BsonField.Name("a2") -> Ascending, 
                                BsonField.Name("a3") -> Ascending, 
                                BsonField.Name("a4") -> Ascending, 
                                BsonField.Name("a5") -> Ascending, 
                                BsonField.Name("a6") -> Ascending
              ))
            ))
          )
        )
    }.pendingUntilFixed

    "plan count grouped by single field" in {
      plan("select count(*) from bar group by baz") must
        beWorkflow {
          PipelineTask(
            ReadTask(Collection("bar")),
            Pipeline(List(
              Project(
                Reshape.Doc(ListMap(
                  BsonField.Name("lEft")  -> \/-  (Reshape.Arr(ListMap(
                                                    BsonField.Index(0) -> -\/ (ExprOp.DocField(BsonField.Name("baz")))))), 
                  BsonField.Name("rIght") -> \/-  (Reshape.Doc(ListMap(
                                                    BsonField.Name("rIght") -> \/-  (Reshape.Doc(ListMap(
                                                                                      BsonField.Name("expr") -> -\/ (ExprOp.Literal(Bson.Int32(1)))))))))))), 
              Group(Grouped(ListMap(
                BsonField.Name("0") -> ExprOp.Sum(ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("expr"))))), -\/ (ExprOp.DocField(BsonField.Name("lEft")))))))
        }
    }

    "plan count and sum grouped by single field" in {
      plan("select count(*) as cnt, sum(biz) as sm from bar group by baz") must
        beWorkflow {
          PipelineTask(ReadTask(Collection("bar")),Pipeline(List(Group(Grouped(ListMap(BsonField.Name("__sd_tmp_1") -> ExprOp.Sum(ExprOp.Literal(Bson.Int32(1))), BsonField.Name("__sd_tmp_2") -> ExprOp.Sum(ExprOp.DocField(BsonField.Name("biz"))))),\/-(Reshape.Arr(ListMap(BsonField.Index(0) -> \/-(Reshape.Arr(ListMap(BsonField.Index(0) -> -\/(ExprOp.DocField(BsonField.Name("baz")))))), BsonField.Index(1) -> \/-(Reshape.Arr(ListMap(BsonField.Index(0) -> -\/(ExprOp.DocField(BsonField.Name("baz")))))))))), Project(Reshape.Doc(ListMap(BsonField.Name("cnt") -> -\/(ExprOp.DocField(BsonField.Name("__sd_tmp_1"))), BsonField.Name("sm") -> -\/(ExprOp.DocField(BsonField.Name("__sd_tmp_2")))))))))
        }
    }

    "plan count and field when grouped" in {
      // TODO: Technically we need a 'distinct by city' here
      plan("select count(*) as cnt, city from zips group by city") must
        beWorkflow {
          PipelineTask(
            ReadTask(Collection("zips")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(
                BsonField.Name("lEft") -> \/- (Reshape.Doc(ListMap(
                                                BsonField.Name("lEft") -> \/- (Reshape.Arr(ListMap(
                                                  BsonField.Index(0) -> -\/ (ExprOp.DocField(BsonField.Name("city")))))), 
                                                BsonField.Name("rIght") -> \/- (Reshape.Doc(ListMap(
                                                  BsonField.Name("rIght") -> \/- (Reshape.Doc(ListMap(
                                                    BsonField.Name("expr") -> -\/(ExprOp.Literal(Bson.Int32(1)))))))))))), 
                BsonField.Name("rIght") -> \/- (Reshape.Doc(ListMap(
                  BsonField.Name("city") -> -\/ (ExprOp.DocField(BsonField.Name("city"))))))))), 
              Group(Grouped(ListMap(
                BsonField.Name("cnt") -> ExprOp.Sum(ExprOp.DocField(BsonField.Name("lEft") \ BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("expr"))), 
                BsonField.Name("__sd_tmp_1") -> ExprOp.Push(ExprOp.DocField(BsonField.Name("rIght"))))),
                -\/(ExprOp.DocField(BsonField.Name("lEft") \ BsonField.Name("lEft")))), 
              Unwind(ExprOp.DocField(BsonField.Name("__sd_tmp_1"))), 
              Project(Reshape.Doc(ListMap(
                BsonField.Name("cnt") -> -\/ (ExprOp.DocField(BsonField.Name("cnt"))), 
                BsonField.Name("city") -> -\/ (ExprOp.DocField(BsonField.Name("__sd_tmp_1") \ BsonField.Name("city")))))))))
        }
    }

    "plan array flatten" in {
      plan("select loc[*] from zips") must
        beWorkflow {
          PipelineTask(
            ReadTask(Collection("zips")),
            Pipeline(List(
              Project(Reshape.Doc(ListMap(BsonField.Name("expr") -> -\/ (ExprOp.DocField(BsonField.Name("loc")))))), 
              Unwind(ExprOp.DocField(BsonField.Name("expr"))), 
              Project(Reshape.Doc(ListMap(BsonField.Name("loc") -> -\/(ExprOp.DocField(BsonField.Name("expr")))))))))
        }
    }
  }

  "plan from LogicalPlan" should {
    "plan simple OrderBy" in {
      val lp = LogicalPlan.Let(
                  'tmp0, read("foo"),
                  LogicalPlan.Let(
                    'tmp1, makeObj("bar" -> ObjectProject(Free('tmp0), Constant(Data.Str("bar")))),
                    LogicalPlan.Let('tmp2, 
                      set.OrderBy(
                        Free('tmp1),
                        MakeArrayN(
                          makeObj(
                            "key" -> ObjectProject(Free('tmp1), Constant(Data.Str("bar"))),
                            "order" -> Constant(Data.Str("ASC"))
                          )
                        )
                      ),
                      Free('tmp2)
                    )
                  )
                )

      val exp = PipelineTask(
        ReadTask(Collection("foo")),
        Pipeline(List(
          Project(Reshape.Doc(ListMap(BsonField.Name("lEft") -> \/-(Reshape.Arr(ListMap(BsonField.Index(0) -> \/-(Reshape.Doc(ListMap(BsonField.Name("key") -> -\/(ExprOp.DocField(BsonField.Name("bar"))), BsonField.Name("order") -> -\/(ExprOp.Literal(Bson.Text("ASC"))))))))), BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("bar") -> -\/(ExprOp.DocField(BsonField.Name("bar")))))))))))), 
          Sort(NonEmptyList(BsonField.Name("lEft") \ BsonField.Index(0) \ BsonField.Name("key") -> Ascending)), 
          Project(Reshape.Doc(ListMap(BsonField.Name("bar") -> -\/(ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("bar")))))))))

      plan(lp) must beWorkflow(exp)
    }

    "plan OrderBy with expression" in {
      val lp = LogicalPlan.Let('tmp0, 
                  read("foo"),
                  set.OrderBy(
                    Free('tmp0),
                    MakeArrayN(
                      makeObj(
                        "key" -> math.Divide(
                                  ObjectProject(Free('tmp0), Constant(Data.Str("bar"))),
                                  Constant(Data.Dec(10.0))),
                        "order" -> Constant(Data.Str("ASC"))
                      )
                    )
                  )
                )

      val exp = PipelineTask(
                  ReadTask(Collection("foo")),
                  Pipeline(List(
                    Project(Reshape.Doc(ListMap(
                      BsonField.Name("__sd_tmp_1") ->  \/- (Reshape.Arr(ListMap(
                        BsonField.Index(0) -> -\/ (ExprOp.Divide(
                                                            DocField(BsonField.Name("bar")), 
                                                            Literal(Bson.Dec(10.0))))
                      )))
                    ))),
                    Sort(NonEmptyList(BsonField.Name("__sd_tmp_1") \ BsonField.Index(0) -> Ascending))
                    // We'll want another Project here to remove the temporary field
                  ))
                )

      plan(lp) must beWorkflow(exp)
    }.pendingUntilFixed

    "plan OrderBy with expression and earlier pipeline op" in {
      val lp = LogicalPlan.Let('tmp0,
                  read("foo"),
                  LogicalPlan.Let('tmp1,
                    set.Filter(
                      Free('tmp0),
                      relations.Eq(
                        ObjectProject(Free('tmp0), Constant(Data.Str("baz"))),
                        Constant(Data.Int(0))
                      )
                    ),
                    set.OrderBy(
                      Free('tmp1),
                      MakeArrayN(
                        makeObj(
                          "key" -> ObjectProject(Free('tmp1), Constant(Data.Str("bar"))),
                          "order" -> Constant(Data.Str("ASC"))
                        )
                      )
                    )
                  )
                )

      val exp = PipelineTask(
                  ReadTask(Collection("foo")),
                  Pipeline(List(
                    Match(
                      Selector.Doc(
                        BsonField.Name("baz") -> Selector.Eq(Bson.Int64(0))
                      )
                    ),
                    Sort(NonEmptyList(BsonField.Name("bar") -> Ascending))
                  ))
                )

      plan(lp) must beWorkflow(exp)
    }.pendingUntilFixed

    "plan OrderBy with expression (and extra project)" in {
      val lp = LogicalPlan.Let('tmp0, 
                  read("foo"),
                  LogicalPlan.Let('tmp9,
                    makeObj(
                      "bar" -> ObjectProject(Free('tmp0), Constant(Data.Str("bar")))
                    ),
                    set.OrderBy(
                      Free('tmp9),
                      MakeArrayN(
                        makeObj(
                          "key" -> math.Divide(
                                    ObjectProject(Free('tmp9), Constant(Data.Str("bar"))),
                                    Constant(Data.Dec(10.0))),
                          "order" -> Constant(Data.Str("ASC"))
                        )
                      )
                    )
                  )
                )

      val exp = PipelineTask(
        ReadTask(Collection("foo")),
        Pipeline(List(
          Project(Reshape.Doc(ListMap(BsonField.Name("lEft") -> \/-(Reshape.Arr(ListMap(BsonField.Index(0) -> \/-(Reshape.Doc(ListMap(BsonField.Name("key") -> -\/(ExprOp.Divide(ExprOp.DocField(BsonField.Name("bar")), ExprOp.Literal(Bson.Dec(10.0)))), BsonField.Name("order") -> -\/(ExprOp.Literal(Bson.Text("ASC"))))))))), BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("rIght") -> \/-(Reshape.Doc(ListMap(BsonField.Name("bar") -> -\/(ExprOp.DocField(BsonField.Name("bar")))))))))))), 
          Sort(NonEmptyList(BsonField.Name("lEft") \ BsonField.Index(0) \ BsonField.Name("key") -> Ascending)), 
          Project(Reshape.Doc(ListMap(BsonField.Name("bar") -> -\/(ExprOp.DocField(BsonField.Name("rIght") \ BsonField.Name("rIght") \ BsonField.Name("bar")))))))))

      plan(lp) must beWorkflow(exp)
    }
  }
}
