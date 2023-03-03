import explicitdeps.ExplicitDepsPlugin.autoImport._
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt.Keys._
import sbt._
import sbtbuildinfo.BuildInfoKeys._
import sbtbuildinfo._
import sbtcrossproject.CrossPlugin.autoImport._
import scalafix.sbt.ScalafixPlugin.autoImport._

object BuildHelper {
  val Scala211: String = "2.11.12"
  val Scala212: String = "2.12.17"
  val Scala213: String = "2.13.10"
  val Scala3: String   = "3.2.2"

  val SilencerVersion = "1.7.12"

  private val stdOptions_ = Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked"
  ) ++ {
    if (sys.env.contains("CI")) {
      Seq("-Xfatal-warnings")
    } else {
      Nil // to enable Scalafix locally
    }
  }

  private val std2xOptions_ = Seq(
    "-language:higherKinds",
    "-language:existentials",
    "-explaintypes",
    "-Yrangepos",
    "-Xlint:_,-missing-interpolator,-type-parameter-shadow",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard"
  )

  private def optimizerOptions_(optimize: Boolean) =
    if (optimize)
      Seq(
        "-opt:l:inline",
        "-opt-inline-from:zio.internal.**"
      )
    else Nil

  def buildInfoSettings_(packageName: String) =
    Seq(
      buildInfoKeys    := Seq[BuildInfoKey](organization, moduleName, name, version, scalaVersion, sbtVersion, isSnapshot),
      buildInfoPackage := packageName
    )

  val dottySettings_ = Seq(
    scalacOptions --= {
      if (scalaVersion.value == Scala3)
        Seq("-Xfatal-warnings")
      else
        Seq()
    },
    Compile / doc / sources  := {
      val old = (Compile / doc / sources).value
      if (scalaVersion.value == Scala3) {
        Nil
      } else {
        old
      }
    },
    Test / parallelExecution := false
  )

  // Keep this consistent with the version in .core-tests/shared/src/test/scala/REPLSpec.scala
  val replSettings_ = makeReplSettings_ {
    """|import zio._
       |import zio.Console._
       |import zio.Duration._
       |import zio.Runtime.default._
       |implicit class RunSyntax[A](io: ZIO[ZEnv, Any, A]){ def unsafeRun: A = Runtime.default.unsafeRun(io.provideLayer(ZEnv.live)) }
    """.stripMargin
  }

  // Keep this consistent with the version in .streams-tests/shared/src/test/scala/StreamREPLSpec.scala
  val streamReplSettings_ = makeReplSettings_ {
    """|import zio._
       |import zio.stream._
       |import zio.Console._
       |import zio.Duration._
       |import zio.Runtime.default._
       |implicit class RunSyntax[A](io: ZIO[ZEnv, Any, A]){ def unsafeRun: A = Runtime.default.unsafeRun(io.provideLayer(ZEnv.live)) }
    """.stripMargin
  }

  def makeReplSettings_(initialCommandsStr: String) = Seq(
    // In the repl most warnings are useless or worse.
    // This is intentionally := as it's more direct to enumerate the few
    // options we do want than to try to subtract off the ones we don't.
    // One of -Ydelambdafy:inline or -Yrepl-class-based must be given to
    // avoid deadlocking on parallel operations, see
    //   https://issues.scala-lang.org/browse/SI-9076
    Compile / console / scalacOptions   := Seq(
      "-Ypartial-unification",
      "-language:higherKinds",
      "-language:existentials",
      "-Yno-adapted-args",
      "-Xsource:2.13",
      "-Yrepl-class-based"
    ),
    Compile / console / initialCommands := initialCommandsStr
  )

  def extraOptions_(scalaVersion: String, optimize: Boolean) =
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((3, 0))  =>
        Seq(
          "-language:implicitConversions",
          "-Xignore-scala2-macros"
        )
      case Some((2, 13)) =>
        Seq(
          "-Ywarn-unused:params,-implicits"
        ) ++ std2xOptions_ ++ optimizerOptions_(optimize)
      case Some((2, 12)) =>
        Seq(
          "-opt-warnings",
          "-Ywarn-extra-implicit",
          "-Ywarn-unused:_,imports",
          "-Ywarn-unused:imports",
          "-Ypartial-unification",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-infer-any",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit",
          "-Ywarn-unused:params,-implicits",
          "-Xfuture",
          "-Xsource:2.13",
          "-Xmax-classfile-name",
          "242"
        ) ++ std2xOptions_ ++ optimizerOptions_(optimize)
      case Some((2, 11)) =>
        Seq(
          "-Ypartial-unification",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-infer-any",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit",
          "-Xexperimental",
          "-Ywarn-unused-import",
          "-Xfuture",
          "-Xsource:2.13",
          "-Xmax-classfile-name",
          "242"
        ) ++ std2xOptions_
      case _             => Seq.empty
    }

  def platformSpecificSources_(platform: String, conf: String, baseDirectory: File)(versions: String*) = for {
    platform <- List("shared", platform)
    version  <- "scala" :: versions.toList.map("scala-" + _)
    result    = baseDirectory.getParentFile / platform.toLowerCase / "src" / conf / version
    if result.exists
  } yield result

  def crossPlatformSources_(scalaVer: String, platform: String, conf: String, baseDir: File) = {
    val versions = CrossVersion.partialVersion(scalaVer) match {
      case Some((2, 11)) =>
        List("2.11+", "2.11-2.12")
      case Some((2, 12)) =>
        List("2.11+", "2.12+", "2.11-2.12", "2.12-2.13")
      case Some((2, 13)) =>
        List("2.11+", "2.12+", "2.13+", "2.12-2.13")
      case Some((3, _))  =>
        List("2.11+", "2.12+", "2.13+")
      case _             =>
        List()
    }
    platformSpecificSources_(platform, conf, baseDir)(versions: _*)
  }

  lazy val crossProjectSettings_ = Seq(
    Compile / unmanagedSourceDirectories ++= {
      crossPlatformSources_(
        scalaVersion.value,
        crossProjectPlatform.value.identifier,
        "main",
        baseDirectory.value
      )
    },
    Test / unmanagedSourceDirectories ++= {
      crossPlatformSources_(
        scalaVersion.value,
        crossProjectPlatform.value.identifier,
        "test",
        baseDirectory.value
      )
    }
  )

  def stdSettings_(prjName: String) = Seq(
    name                                   := s"$prjName",
    crossScalaVersions                     := Seq(Scala211, Scala212, Scala213, Scala3),
    ThisBuild / scalaVersion               := Scala213,
    scalacOptions ++= stdOptions_ ++ extraOptions_(scalaVersion.value, optimize = !isSnapshot.value),
    libraryDependencies ++= {
      if (scalaVersion.value == Scala3)
        Seq(
          "com.github.ghik" % s"silencer-lib_$Scala213" % SilencerVersion % Provided
        )
      else
        Seq(
          "com.github.ghik" % "silencer-lib"            % SilencerVersion % Provided cross CrossVersion.full,
          compilerPlugin("com.github.ghik" % "silencer-plugin" % SilencerVersion cross CrossVersion.full),
          compilerPlugin("org.typelevel"  %% "kind-projector"  % "0.13.2" cross CrossVersion.full)
        )
    },
    semanticdbEnabled                      := scalaVersion.value != Scala3, // enable SemanticDB
    semanticdbOptions += "-P:semanticdb:synthetics:on",
    semanticdbVersion                      := scalafixSemanticdb.revision,  // use Scalafix compatible version
    ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value),
    ThisBuild / scalafixDependencies ++= List(
      "com.github.liancheng" %% "organize-imports" % "0.5.0",
      "com.github.vovapolu"  %% "scaluzzi"         % "0.1.20"
    ),
    Test / parallelExecution               := false,
    incOptions ~= (_.withLogRecompileOnMacro(false)),
    autoAPIMappings                        := true,
    unusedCompileDependenciesFilter -= moduleFilter("org.scala-js", "scalajs-library")
  )

//  def macroExpansionSettings_ = Seq(
//    scalacOptions ++= {
//      CrossVersion.partialVersion(scalaVersion.value) match {
//        case Some((2, 13)) => Seq("-Ymacro-annotations")
//        case _             => Seq.empty
//      }
//    },
//    libraryDependencies ++= {
//      CrossVersion.partialVersion(scalaVersion.value) match {
//        case Some((2, x)) if x <= 12 =>
//          Seq(compilerPlugin(("org.scalamacros" % "paradise" % "2.1.1").cross(CrossVersion.full)))
//        case _                       => Seq.empty
//      }
//    }
//  )

  def macroDefinitionSettings_ = Seq(
    scalacOptions += "-language:experimental.macros",
    libraryDependencies ++= {
      if (scalaVersion.value == Scala3) Seq()
      else
        Seq(
          "org.scala-lang" % "scala-reflect"  % scalaVersion.value % "provided",
          "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"
        )
    }
  )

  def jsSettings_ = Seq(
    Test / fork := crossProjectPlatform.value == JVMPlatform // set fork to `true` on JVM to improve log readability, JS and Native need `false`
  )

  def nativeSettings_ = Seq(
    Test / test := (Test / compile).value,
    Test / fork := crossProjectPlatform.value == JVMPlatform // set fork to `true` on JVM to improve log readability, JS and Native need `false`
  )

  val scalaReflectTestSettings_ : List[Setting[_]] = List(
    libraryDependencies ++= {
      if (scalaVersion.value == Scala3)
        Seq("org.scala-lang" % "scala-reflect" % Scala213           % Test)
      else
        Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value % Test)
    }
  )

  implicit class ModuleHelper(p: Project) {
    def module: Project = p.in(file(p.id)).settings(stdSettings_(p.id))
  }
}
