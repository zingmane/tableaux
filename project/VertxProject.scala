package io.vertx.scala.build

import java.io.File
import java.nio.charset.StandardCharsets

import sbt._
import sbt.Keys._

case class VertxModule(organization: String,
                       id: String,
                       version: String,
                       description: String,
                       scalaVersion: Option[String] = Some("2.11.6"),
                       crossScalaVersions: Seq[String] = Seq.empty)


trait VertxProject extends Build {

  def module: VertxModule

  def base: File = file(".")

  lazy val copyMod = TaskKey[File]("copy-mod", "Assemble the module into the local mods directory")
  lazy val zipMod = TaskKey[File]("zip-mod", "Package the module .zip file")
  lazy val pullInDeps = TaskKey[Unit]("pull-in-deps", "Pulls all dependencies into the module")
  lazy val fatJar = TaskKey[Unit]("fat-jar", "Creates a fat jar file for deployment on the Server")
  lazy val runnableModule = TaskKey[Unit]("runnable-module", "The module is ready to use here")
  lazy val runMod = TaskKey[Unit]("run-mod", "runs the module")
  lazy val copyLibDependencies = TaskKey[Unit]("copy-lib-dependencies", "Copy all runtime classpath dependencies")

  def customSettings: Seq[Setting[_]] = Seq.empty

  def vertxVersion: String = "2.1.5"

  def vertxScalaVersion: String = "1.1.0-M1"

  def dependencies: Seq[ModuleID] = Seq.empty

  lazy val project = Project(
    id = module.id,
    base = base,
    settings = vertxSettings ++ vertxTasks ++ customSettings
  )

  private lazy val baseSettings: Seq[Setting[_]] = Seq(
    organization := module.organization,
    name := module.id,
    version := module.version,
    description := module.description
  ) ++ module.scalaVersion.map(scalaVersion := _).toList ++ Seq(
    crossScalaVersions := module.crossScalaVersions
  )

  lazy val vertxSettings: Seq[Setting[_]] = baseSettings ++ Seq(
    libraryDependencies ++= Seq(
      "io.vertx" % "vertx-core" % vertxVersion % "provided",
      "io.vertx" % "vertx-platform" % vertxVersion % "provided",

      "io.vertx" % "testtools" % "2.0.3-final" % "test",
      "org.hamcrest" % "hamcrest-library" % "1.3" % "test",
      "com.novocode" % "junit-interface" % "0.10" % "test"
    ) ++ module.scalaVersion.map(_ => "io.vertx" %% "lang-scala" % vertxScalaVersion % "provided").toList,

    libraryDependencies ++= dependencies,

    // Add hazelcast to fatJar only
    libraryDependencies in fatJar += "io.vertx" % "vertx-hazelcast" % vertxVersion,

    // Fork JVM to allow Scala in-flight compilation tests to load the Scala interpreter
    fork in Test := true,

    // Vert.x tests are not designed to run in parallel
    parallelExecution in Test := false,

    baseDirectory in Test := target.value,

    // debug
    javaOptions in Test += "-Ddebug.basedir=" + baseDirectory.value,
    javaOptions in Test += "-Ddebug.target=" + target.value,
    // Adjust test system properties so that scripts are found
    javaOptions in Test += "-Dvertx.test.resources=src/test/resources",
    // Adjust test modules directory
    javaOptions in Test += "-Dvertx.clusterManagerFactory=org.vertx.java.spi.cluster.impl.hazelcast.HazelcastClusterManagerFactory",
    javaOptions in Test += "-Dvertx.mods=mods",
    // Set the module name for tests
    javaOptions in Test += s"-Dvertx.modulename=${moduleInfo.value._1}"
  ) ++ module.scalaVersion.map { _ =>
    resourceGenerators in Compile += Def.task {
      val file = (resourceManaged in Compile).value / "langs.properties"

      val scala = s"scala=io.vertx~lang-scala_${getMajor(scalaVersion.value)}~$vertxScalaVersion:org.vertx.scala.platform.impl.ScalaVerticleFactory\n.scala=scala\n"
      val javascript = s"rhino=io.vertx~lang-rhino~2.1.1:org.vertx.java.platform.impl.RhinoVerticleFactory\n.js=rhino\n"

      IO.write(file, scala, StandardCharsets.UTF_8)
      IO.append(file, javascript, StandardCharsets.UTF_8)

      val platformLibDir = (resourceManaged in Compile).value / "platform_lib"
      IO.createDirectory(platformLibDir)

      val platformLibFile = platformLibDir / "langs.properties"

      IO.write(platformLibFile, scala, StandardCharsets.UTF_8)
      IO.append(platformLibFile, javascript, StandardCharsets.UTF_8)

      Seq(file, platformLibFile)
    }.taskValue
  }.toList ++ Seq(
    // Publishing settings
    publishMavenStyle := true,
    pomIncludeRepository := { _ => false},
    publishTo <<= version { (v: String) =>
      val sonatype = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("Sonatype Snapshots" at sonatype + "content/repositories/snapshots")
      else
        Some("Sonatype Releases" at sonatype + "service/local/staging/deploy/maven2")
    },
    pomExtra := pomExtra.value
  ) ++ addArtifact(Artifact(module.id, "zip", "zip", "mod"), zipMod).settings

  lazy val vertxTasks: Seq[Setting[_]] = Seq(
    copyModTask,
    zipModTask,
    runModTask,
    pullInDepsTask,
    fatJarTask,
    copyLibDependenciesTask,

    copyMod <<= copyMod dependsOn ((copyResources in Compile), copyLibDependencies),

    runMod <<= runMod dependsOn copyMod,
    zipMod <<= zipMod dependsOn copyMod,
    fatJar <<= fatJar dependsOn (copyMod, pullInDeps),

    (test in Test) <<= (test in Test) dependsOn copyMod,
    (packageBin in Compile) <<= (packageBin in Compile) dependsOn copyMod
  )

  lazy val runnableModuleTask = runnableModule := {}

  lazy val moduleInfo = Def.setting {
    val modOwner = organization.value
    val modName = name.value
    val modVersion = version.value
    val scalaMajor = getMajor(scalaVersion.value)
    val moduleName = s"$modOwner~${modName}_$scalaMajor~$modVersion"
    val moduleDir = target.value / "mods" / moduleName

    (moduleName, moduleDir)
  }

  lazy val copyModTask = copyMod := {
    implicit val log = streams.value.log

    val (moduleName, moduleDir) = moduleInfo.value

    log.info("Create module " + moduleName)

    createDirectory(moduleDir)
    copyDirectory((classDirectory in Compile).value, moduleDir)

    moduleDir
  }

  lazy val copyLibDependenciesTask = copyLibDependencies := {
    implicit val log = streams.value.log

    val moduleDir = moduleInfo.value._2

    log.info("Copy all runtime classpath dependencies")

    val libDir = moduleDir / "lib"
    createDirectory(libDir)

    // Get the runtime classpath to get all dependencies except provided ones
    val classpath = (managedClasspath in Runtime).value

    // Ignore scala-library (fatJar can't be executed if it's included)
    classpath filter { e => !e.data.name.contains("scala-library") } foreach { classpathEntry =>
      copyClasspathFile(classpathEntry, libDir)
    }
  }

  lazy val zipModTask = zipMod := {
    implicit val log = streams.value.log
    val (moduleName, moduleDir) = moduleInfo.value
    log.info("Create ZIP module " + moduleName)
    val zipFile = target.value / "zips" / s"$moduleName.zip"
    IO.zip(allSubpaths(moduleDir), zipFile)
    zipFile
  }

  lazy val pullInDepsTask = pullInDeps := {
    val (moduleName, moduleDir) = moduleInfo.value

    val r = (runner in Test).value
    val args = Seq("pulldeps", moduleName)
    val cp = (fullClasspath in Test).value

    System.setProperty("vertx.mods", (target.value / "mods").toString)
    System.setProperty("vertx.modulename", moduleName)
    toError(r.run("org.vertx.java.platform.impl.cli.Starter", cp.map(_.data), args, streams.value.log))
  }

  lazy val fatJarTask = fatJar := {
    implicit val log = streams.value.log
    val (moduleName, moduleDir) = moduleInfo.value

    val deployDir = target.value / "deploy"
    createDirectory(deployDir)

    val r = (runner in Test).value
    val args = Seq("fatjar", moduleName, "-d", deployDir.toString)
    val cp = (fullClasspath in Test).value

    System.setProperty("vertx.mods", (target.value / "mods").toString)
    System.setProperty("vertx.modulename", moduleName)
    toError(r.run("org.vertx.java.platform.impl.cli.Starter", cp.map(_.data), args, log))
  }

  lazy val runModTask = runMod := {
    val log = streams.value.log
    val (moduleName, moduleDir) = moduleInfo.value

    val r = (runner in Test).value
    val args = Seq("runmod", moduleName, "-conf", (target.value / ".." / "conf.json").toString)
    val cp = (fullClasspath in Test).value

    System.setProperty("vertx.mods", (target.value / "mods").toString)
    System.setProperty("vertx.modulename", moduleName)
    log.info(s"args=$args, cp=$cp")
    toError(r.run("org.vertx.java.platform.impl.cli.Starter", cp.map(_.data), args, log))
  }

  private def getMajor(version: String): String = version.substring(0, version.lastIndexOf('.'))

  private def createDirectory(dir: File)(implicit log: Logger): Unit = {
    log.debug(s"Create directory $dir")
    IO.createDirectory(dir)
  }

  private def copyDirectory(source: File, target: File)(implicit log: Logger): Unit = {
    log.debug(s"Copy $source to $target")
    IO.copyDirectory(source, target, overwrite = true)
  }

  private def copyClasspathFile(cpEntry: Attributed[File], libDir: File)(implicit log: Logger): Unit = {
    val sourceFile = cpEntry.data
    val targetFile = libDir / sourceFile.getName
    log.debug(s"Copy $sourceFile to $targetFile")
    IO.copyFile(sourceFile, targetFile)
  }
}