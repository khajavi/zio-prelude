import BuildHelper._
import zio.sbt.ZioSbtCiPlugin._
import zio.sbt.githubactions.{Condition, DependencyBot, Job, Step, Strategy}

inThisBuild(
  List(
    organization := "dev.zio",
    homepage     := Some(url("https://zio.dev/zio-prelude/")),
    licenses     := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers   := List(
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    ),

    // zio-sbt-ci settings: keep the generated ci.yml matching what this project's handwritten
    // workflow already tested (master + zio-2, one grouped test job per Scala version covering
    // all platforms at JDK 17, a JDK 11 JVM-only re-run, and JDK 8 for the build/publish/release
    // jobs since artifacts are published for JDK 8) rather than the plugin's stock defaults.
    ciEnabledBranches     := Seq("master", "zio-2"),
    // Preserves the memory/stack tuning the old workflow applied to every job, and the swap space
    // that cross-building JVM+JS+Native across four Scala versions needed to avoid OOM/disk
    // pressure. `JVM_OPTS` (rather than `SBT_OPTS`) matches the old workflow's naming, since it's
    // read by the JDK 8 jobs, which don't understand `JDK_JAVA_OPTIONS`.
    ciJvmOptions          := Seq("-XX:MaxMetaspaceSize=4G", "-Xmx4G", "-Xss8M"),
    ciWorkflowEnv         := {
      val flags = ("-XX:+PrintCommandLineFlags" +: ciJvmOptions.value).mkString(" ")
      Map("JDK_JAVA_OPTIONS" -> flags, "JVM_OPTS" -> flags)
    },
    ciSwapSizeGB          := 7,
    ciDependencyUpdateBots := Seq(
      DependencyBot.Dependabot,
      DependencyBot.Renovate,
      DependencyBot.Custom("scala-steward")
    ),
    ciTargetScalaVersions := targetScalaVersionsFor(
      (projectsCommon.flatMap(p => Seq(p.jvm, p.js, p.native)) ++ Seq(benchmarks, scalaParallelCollections)): _*
    ).value,
    ciGroupSimilarTests  := true,
    ciTargetJavaVersions := Seq("17"),
    // JDK-8-only re-run of the JVM projects, mirroring the old `testJvms` job. JDK 17 is already
    // covered by the grouped `test` job above, so only 11 is added here.
    ciTestJobs           := ciTestJobs.value :+ Job(
      id = "testJvms",
      name = "Test JVMs",
      strategy = Some(Strategy(matrix = Map("java" -> List("11")), failFast = false)),
      steps = (if (ciSwapSizeGB.value > 0) Seq(SetSwapSpace.value) else Seq.empty) ++ Seq(
        Checkout.value,
        SetupLibuv,
        SetupJava("${{ matrix.java }}"),
        SetupSBT,
        CacheDependencies,
        Step.SingleStep(
          name = "Test",
          run = Some(
            "sbt --no-colors " + Seq(
              "coreJVM",
              "coreTestsJVM",
              "lawsJVM",
              "macrosJVM",
              "experimentalJVM",
              "experimentalLawsJVM",
              "experimentalTestsJVM",
              "examplesJVM",
              "benchmarks",
              "scalaParallelCollections"
            ).map(_ + "/test").mkString(" ")
          )
        )
      )
    ),
    // The build (compile + publishLocal + website) and release jobs must run on JDK 8: published
    // artifacts target JDK 8, which is only actually exercised by compiling under it.
    ciBuildJobs           := ciBuildJobs.value.map(onJava8),
    // The plugin's default snapshot condition only publishes pushes to the *default* branch; the
    // old workflow published (or, on a tag, released) on any non-pull-request event, which
    // includes pushes to `zio-2`. Restore that so `zio-2` keeps getting SNAPSHOT publishes.
    ciReleaseJobs         := ciReleaseJobs.value.map(onJava8).map {
      case job if job.id == "release" =>
        job.copy(condition =
          Some(
            (Condition.Expression("github.event_name == 'release'") && Condition.Expression(
              "github.event.action == 'published'"
            )) ||
              Condition.Expression("github.event_name == 'workflow_dispatch'") ||
              Condition.Expression("github.event_name == 'push'")
          )
        )
      case job                        => job
    }
  )
)

def onJava8(job: Job): Job =
  job.copy(steps = job.steps.map {
    case s: Step.SingleStep if s.name == "Setup Scala" => SetupJava("8")
    case other                                         => other
  })

addCommandAlias("fix", "; all compile:scalafix test:scalafix; all scalafmtSbt scalafmtAll")
addCommandAlias(
  "check",
  "; scalafmtSbtCheck; scalafmtCheckAll; Test/compile; compile:scalafix --check; test:scalafix --check"
)
// The `lint` job zio-sbt-ci generates by default runs `sbt lint`; alias it to this project's own
// formatting/scalafix check rather than pulling in zio-sbt-ecosystem for its `lint` command.
addCommandAlias("lint", "check")

val zioVersion = "1.0.17"

val projectsCommon = List(
  core,
  coreTests,
  examples,
  experimental,
  experimentalLaws,
  experimentalTests,
  laws,
  macros
)

val projectsJvmOnly = List[ProjectReference](
  benchmarks,
  scalaParallelCollections
)

lazy val root = project
  .in(file("."))
  .settings(
    publish / skip     := true,
    // `Nil` rather than the default `Seq(scalaVersion.value)`: `root` isn't itself cross-built,
    // and this is what lets top-level `+Test/compile`/`+publishLocal` (as run by the zio-sbt-ci
    // `build` job) cross-build over each aggregated project's own `crossScalaVersions` instead of
    // just root's.
    crossScalaVersions := Nil
  )
  .aggregate(projectsCommon.flatMap(p => List[ProjectReference](p.jvm, p.js, p.native)): _*)
  .aggregate(projectsJvmOnly: _*)

lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("core"))
  .settings(stdSettings("zio-prelude"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(buildInfoSettings("zio.prelude"))
  .settings(Compile / console / scalacOptions ~= { _.filterNot(Set("-Xfatal-warnings")) })
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"         % zioVersion,
      "dev.zio" %%% "zio-streams" % zioVersion
    )
  )
  .settings(dottySettings)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(macros)

lazy val coreTests = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("core-tests"))
  .settings(stdSettings("zio-prelude-tests"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(buildInfoSettings("zio.prelude.tests"))
  .settings(Compile / console / scalacOptions ~= { _.filterNot(Set("-Xfatal-warnings")) })
  .settings(testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")))
  .settings(dottySettings)
  .settings(libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test)
  .jvmSettings(scalaReflectTestSettings)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(laws)
  .settings(publish / skip := true)

lazy val laws = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("laws"))
  .settings(stdSettings("zio-laws-laws"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(buildInfoSettings("zio.prelude.laws"))
  .settings(Compile / console / scalacOptions ~= { _.filterNot(Set("-Xfatal-warnings")) })
  .settings(libraryDependencies += "dev.zio" %%% "zio-test" % zioVersion)
  .settings(testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")))
  .settings(dottySettings)
  .settings(libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test)
  .jvmSettings(scalaReflectTestSettings)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(core)

lazy val macros = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("macros"))
  .settings(stdSettings("zio-prelude-macros"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(buildInfoSettings("zio.prelude.macros"))
  .settings(Compile / console / scalacOptions ~= { _.filterNot(Set("-Xfatal-warnings")) })
  .settings(dottySettings)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)
  .enablePlugins(BuildInfoPlugin)

lazy val experimental = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("experimental"))
  .dependsOn(core)
  .settings(stdSettings("zio-prelude-experimental"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.prelude.experimental"))
  .settings(testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")))
  .settings(dottySettings)
  .settings(libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test)
  .jvmSettings(scalaReflectTestSettings)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)
  .enablePlugins(BuildInfoPlugin)

lazy val experimentalLaws = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("experimental-laws"))
  .dependsOn(experimental, laws)
  .settings(stdSettings("zio-prelude-experimental-laws"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.prelude.experimental.laws"))
  .settings(libraryDependencies += "dev.zio" %%% "zio-test" % zioVersion)
  .settings(testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")))
  .settings(dottySettings)
  .settings(libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test)
  .jvmSettings(scalaReflectTestSettings)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)
  .enablePlugins(BuildInfoPlugin)

lazy val experimentalTests = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("experimental-tests"))
  .dependsOn(experimentalLaws)
  .settings(stdSettings("zio-prelude-experimental-tests"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.prelude.experimental.tests"))
  .settings(testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")))
  .settings(dottySettings)
  .settings(libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test)
  .jvmSettings(scalaReflectTestSettings)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)
  .enablePlugins(BuildInfoPlugin)

lazy val scalaParallelCollections = project
  .in(file("scala-parallel-collections"))
  .dependsOn(core.jvm, coreTests.jvm % "test->test")
  .settings(stdSettings("zio-prelude-scala-parallel-collections"))
  .settings(buildInfoSettings("zio.prelude.scalaparallelcollections"))
  .settings(testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")))
  .settings(dottySettings)
  .settings(
    libraryDependencies ++= {
      scalaVersion.value match {
        // Only 2.11 and 2.12 standard library contains Parallel Scala collections
        case BuildHelper.Scala211 | BuildHelper.Scala212 =>
          List()
        case _                                           =>
          List("org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4")
      }
    }
  )
  .settings(libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test)
  .settings(scalaReflectTestSettings)
  .enablePlugins(BuildInfoPlugin)

lazy val benchmarks = project
  .in(file("benchmarks"))
  .settings(stdSettings("zio-prelude-benchmarks"))
  .settings(
    crossScalaVersions --= List(BuildHelper.Scala211),
    publish / skip := true,
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.9.0"
    )
  )
  .dependsOn(core.jvm)
  .enablePlugins(JmhPlugin)

lazy val docs = project
  .in(file("zio-prelude-docs"))
  .settings(
    scalaVersion      := BuildHelper.Scala213,
    publish / skip    := true,
    moduleName        := "zio-prelude-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName     := "ZIO Prelude",
    projectStage    := ProjectStage.ProductionReady,
    badgeArtifactId := "zio-prelude_2.12"
  )
  .settings(macroDefinitionSettings)
  .dependsOn(core.jvm, experimental.jvm, experimentalLaws.jvm, laws.jvm, scalaParallelCollections)
  .enablePlugins(WebsitePlugin)

lazy val examples =
  crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .in(file("examples"))
    .dependsOn(core)
    .settings(stdSettings("zio-prelude-examples"))
    .settings(crossProjectSettings)
    .settings(macroExpansionSettings)
    .settings(publish / skip := true)
