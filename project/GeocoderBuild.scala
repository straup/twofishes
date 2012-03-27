import sbt._
import Keys._

object GeocoderBuild extends Build {
  lazy val buildSettings = Seq(
    organization := "com.foursquare.twofish",
    name := "twofish",
    version      := "0.12",
    scalaVersion := "2.9.1"
  )

  lazy val defaultSettings = super.settings ++ buildSettings ++ Defaults.defaultSettings ++ Seq(
    resolvers += "geomajas" at "http://maven.geomajas.org",
    resolvers += "twitter" at "http://maven.twttr.com",
    resolvers += "repo.novus rels" at "http://repo.novus.com/releases/",
    resolvers += "repo.novus snaps" at "http://repo.novus.com/snapshots/",
    resolvers += "Java.net Maven 2 Repo" at "http://download.java.net/maven/2",
    resolvers ++= Seq("snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
                      "releases"  at "http://oss.sonatype.org/content/repositories/releases"),

    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    publishTo := Some("foursquare Nexus" at "http://nexus.prod.foursquare.com/nexus/content/repositories/releases/"),
    Keys.publishArtifact in (Compile, Keys.packageDoc) := false,

    pomExtra := (
      <url>http://github.com/foursquare/twofish</url>
      <licenses>
        <license>
          <name>Apache</name>
          <url>http://www.opensource.org/licenses/Apache-2.0</url>
          <distribution>repo</distribution>
        </license>
      </licenses>
      <scm>
        <url>git@github.com:foursquare/twofish.git</url>
        <connection>scm:git:git@github.com:foursquare/twofish.git</connection>
      </scm>
      <developers>
        <developer>
          <id>blackmad</id>
          <name>David Blackman</name>
          <url>http://github.com/blackmad</url>
        </developer>
      </developers>),

    credentials ++= {
      val sonatype = ("Sonatype Nexus Repository Manager", "repo.foursquare.com")
      def loadMavenCredentials(file: java.io.File) : Seq[Credentials] = {
        xml.XML.loadFile(file) \ "servers" \ "server" map (s => {
          val host = (s \ "id").text
          val realm = if (host == sonatype._2) sonatype._1 else "Unknown"
          Credentials(realm, host, (s \ "username").text, (s \ "password").text)
        })  
      }   
      val ivyCredentials   = Path.userHome / ".ivy2" / ".credentials"
      val mavenCredentials = Path.userHome / ".m2"   / "settings.xml"
      (ivyCredentials.asFile, mavenCredentials.asFile) match {
        case (ivy, _) if ivy.canRead => Credentials(ivy) :: Nil
        case (_, mvn) if mvn.canRead => loadMavenCredentials(mvn)
        case _ => Nil
      }
    }
  )

  lazy val all = Project(id = "all",
    settings = defaultSettings ++ Seq(
      publishArtifact := true
    ),
    base = file(".")) aggregate(core, interface, server, indexer)

  lazy val core = Project(id = "core",
      base = file("core"),
      settings = defaultSettings ++ Seq(
        publishArtifact := true,
        libraryDependencies ++= Seq(
          "com.twitter" % "util-core_2.9.1" % "1.12.8",
          "com.twitter" % "util-logging_2.9.1" % "1.12.8",
          "org.slf4j" % "slf4j-api" % "1.6.1",
          "com.novus" % "salat-core_2.9.1" % "0.0.8-SNAPSHOT"
        )
      )
    ) dependsOn(interface)

  lazy val interface = Project(id = "interface",
      settings = defaultSettings ++ Seq(
        publishArtifact := true,
        libraryDependencies ++= Seq(
          "thrift" % "libthrift" % "0.5.0" from "http://maven.twttr.com/org/apache/thrift/libthrift/0.5.0/libthrift-0.5.0.jar",
          "com.twitter" % "finagle-thrift_2.9.1" % "1.9.12",
          "org.slf4j" % "slf4j-api" % "1.6.1"
        )
      ),
      base = file("interface"))

  lazy val server = Project(id = "server",
      settings = defaultSettings ++ Seq(
        publishArtifact := true,
        libraryDependencies ++= Seq(
          "com.twitter" % "finagle-http_2.9.1" % "1.9.12",
          "org.specs2" %% "specs2" % "1.8.2" % "test",
          "org.scala-tools.testing" %% "specs" % "1.6.9" % "test",
          "com.github.scopt" %% "scopt" % "2.0.0"
        )
      ),
      base = file("server")) dependsOn(core, interface)

  lazy val indexer = Project(id = "indexer",
      base = file("indexer"),
      settings = defaultSettings ++ Seq(
        publishArtifact := false,
        libraryDependencies ++= Seq(
          "com.twitter" % "util-core_2.9.1" % "1.12.8",
          "com.twitter" % "util-logging_2.9.1" % "1.12.8",
          "com.novus" % "salat-core_2.9.1" % "0.0.8-SNAPSHOT",
          "com.github.scopt" %% "scopt" % "2.0.0"
        )
      )
  ) dependsOn(core)
}
