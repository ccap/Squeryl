resolvers := Seq(
  "CCAP Repository" at "https://repoman.wicourts.gov/artifactory/scala",
  "CCAP Releases" at "https://repoman.wicourts.gov/artifactory/libs-release-local",
  "CCAP Snapshots" at "https://repoman.wicourts.gov/artifactory/libs-snapshot-local"
)

lazy val squeryl = Project("squeryl", file(".")).settings(
  Seq(
    description := "A Scala ORM and DSL for talking with Databases using minimum verbosity and maximum type safety",
    organization := "gov.wicourts.org.squeryl",
    version := "0.9.6-ccap40",
    javacOptions := Seq("-source", "1.8", "-target", "1.8"),
    /*
  	version <<= version { v => //only release *if* -Drelease=true is passed to JVM
  	 val release = Option(System.getProperty("release")) == Some("true")
  	 if(release)
  	  v
  	 else {
  	  val suffix = Option(System.getProperty("suffix"))
  	  val i = (v.indexOf('-'), v.length) match {
  	  		case (x, l) if x < 0 => l
  	  		case (x, l) if v substring (x+1) matches """\d+""" => l //patch level, not RCx
  	  		case (x, _) => x
  	  }
  	  v.substring(0,i) + "-" + (suffix getOrElse "SNAPSHOT")
  	 }
  	},
     */
    parallelExecution := false,
    publishMavenStyle := true,
    scalaVersion := "2.12.15",
    crossScalaVersions := Seq("2.12.15", "2.13.0-RC1"),
    licenses := Seq(
      "Apache 2" -> url(
        "http://www.apache.org/licenses/LICENSE-2.0.txt"
      )
    ),
    homepage := Some(url("http://squeryl.org")),
    pomExtra := (<scm>
                   <url>git@github.com:max-l/squeryl.git</url>
                   <connection>scm:git:git@github.com:max-l/squeryl.git</connection>
                 </scm>
                 <developers>
                   <developer>
                     <id>max-l</id>
                     <name>Maxime Lévesque</name>
                     <url>https://github.com/max-l</url>
                   </developer>
                   <developer>
                     <id>davewhittaker</id>
                     <name>Dave Whittaker</name>
                     <url>https://github.com/davewhittaker</url>
                   </developer>
                 </developers>),
    publishTo := Some(
      "CCAP Releases" at "https://repoman.wicourts.gov/artifactory/libs-release-local"
    ),
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    Test / publishArtifact := false,
    pomIncludeRepository := { _ => false },
    //below is for lsync, run "ls-write-version", commit to github, then run "lsync"
    /*
			(LsKeys.tags in LsKeys.lsync) := Seq("sql", "orm", "query", "database", "db", "dsl"),
			(LsKeys.docsUrl in LsKeys.lsync) := Some(new URL("http://squeryl.org/api/")),
			(LsKeys.ghUser in LsKeys.lsync) := Some("max-l"),
     */
    libraryDependencies ++= Seq(
      "cglib" % "cglib-nodep" % "2.2",
      "com.h2database" % "h2" % "1.2.127" % "provided",
      "mysql" % "mysql-connector-java" % "5.1.10" % "provided",
      "postgresql" % "postgresql" % "8.4-701.jdbc4" % "provided",
      "net.sourceforge.jtds" % "jtds" % "1.2.4" % "provided",
      "org.apache.derby" % "derby" % "10.7.1.1" % "provided",
      "junit" % "junit" % "4.8.2" % "provided",
      "org.scalatest" %% "scalatest" % "3.0.3" % "test",
      "org.scala-lang" % "scala-reflect" % "2.12.2",
      "org.scala-lang.modules" %% "scala-xml" % "1.0.5",
      "org.scalaz" %% "scalaz-core" % "7.2.18"
    )
  )
)
