package quasar.project

import sbt._, Keys._
import wartremover._
import scala.Seq

object Ygg {
  def imports = """
    import quasar._, Predef._
    import java.nio.file._
    import java.time._
    import scalaz._, Scalaz._
    import matryoshka._, Recursive.ops._, FunctorT.ops._, TraverseT.nonInheritedOps._
  """.trim

  def yggImports = imports + "\n" + """
    import ygg._, common._, json._, table._, trans._
    import quasar._, sql._, SemanticAnalysis._
    import ygg.macros.Json._
  """.trim

  def jsonfileImports = yggImports + "\n" + """
    import quasar.physical.jsonfile.fs._, FallbackJV._
    val mf = quasar.qscript.MapFuncs
  """.trim

  def yggDropWarts = Seq(
    Wart.Equals,
    Wart.AsInstanceOf,
    Wart.Overloading,
    Wart.ToString,
    Wart.NoNeedForMonad,
    Wart.Null,
    Wart.Var,
    Wart.MutableDataStructures,
    Wart.NonUnitStatements,
    Wart.Return,
    Wart.While,
    Wart.ListOps,
    Wart.Throw,
    Wart.OptionPartial,
    Wart.Option2Iterable
  )

  def macros(p: Project): Project = ( p
    .dependsOn('foundation % BothScopes, 'frontend)
    .settings(name := "quasar-macros-internal")
    .settings(wartremoverWarnings in (Compile, compile) --= yggDropWarts)
    .settings(scalacOptions += "-language:experimental.macros")
  )

  def ygg(p: Project): Project = ( p
    .dependsOn('foundation % BothScopes, 'macros, 'ejson, 'connector)
    .settings(name := "quasar-ygg-internal")
    .settings(scalacOptions ++= Seq("-language:_"))
    .settings(libraryDependencies ++= Dependencies.ygg)
    .settings(wartremoverWarnings in (Compile, compile) --= yggDropWarts)
    .settings(initialCommands in (Compile, console) := yggImports)
    .settings(scalacOptions in (Compile, console) --= Seq("-Ywarn-unused-code"))
  )

  def jsonfile(p: Project): Project = ( p
    .dependsOn('connector % BothScopes, 'ygg % BothScopes, 'sql)
    .settings(name := "quasar-jsonfile-internal")
    .settings(wartremoverWarnings in (Compile, compile) --= yggDropWarts)
    .settings(initialCommands in (Compile, console) := jsonfileImports)
  )
}
