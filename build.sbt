
sonatypeProfileName := "com.github.j5ik2o"

organization := "com.github.j5ik2o"

name := """akka-backoff-supervisor-enhancement"""

scalaVersion := "2.11.8"

crossScalaVersions := Seq("2.11.8", "2.12.1")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.11" % "provided",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.11" % "test",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)

scalacOptions ++= Seq(
  "-feature"
  , "-deprecation"
  , "-unchecked"
  , "-encoding"
  , "UTF-8"
  , "-Xfatal-warnings"
  , "-language:existentials"
  , "-language:implicitConversions"
  , "-language:postfixOps"
  , "-language:higherKinds"
  //    , "-Yinline-warnings" // Emit inlining warnings. (Normally surpressed due to high volume)
  , "-Ywarn-adapted-args" // Warn if an argument list is modified to match the receiver
  , "-Ywarn-dead-code" // Warn when dead code is identified.
  , "-Ywarn-inaccessible" // Warn about inaccessible types in method signatures.
  , "-Ywarn-infer-any" // Warn when a type argument is inferred to be `Any`.
  , "-Ywarn-nullary-override" // Warn when non-nullary `def f()' overrides nullary `def f'
  , "-Ywarn-nullary-unit" // Warn when nullary methods return Unit.
  , "-Ywarn-numeric-widen" // Warn when numerics are widened.
  //    , "-Ywarn-unused" // Warn when local and private vals, vars, defs, and types are are unused.
  //, "-Ywarn-unused-import" // Warn when imports are unused.
  , "-Xmax-classfile-name", "200"
)

resolvers ++= Seq(
  "Sonatype OSS Release Repository" at "https://oss.sonatype.org/content/repositories/releases/"
)

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := {
  _ => false
}

pomExtra := {
  <url>https://github.com/j5ik2o/akka-backoff-supervisor-enhancement</url>
    <licenses>
      <license>
        <name>The MIT License</name>
        <url>http://opensource.org/licenses/MIT</url>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:j5ik2o/akka-backoff-supervisor-enhancement.git</url>
      <connection>scm:git:github.com/j5ik2o/akka-backoff-supervisor-enhancement</connection>
      <developerConnection>scm:git:git@github.com:j5ik2o/akka-backoff-supervisor-enhancement.git</developerConnection>
    </scm>
    <developers>
      <developer>
        <id>j5ik2o</id>
        <name>Junichi Kato</name>
      </developer>
    </developers>
}

credentials := Def.task {
  val ivyCredentials = (baseDirectory in LocalRootProject).value / ".credentials"
  val result = Credentials(ivyCredentials) :: Nil
  result
}.value

scalariformSettings

