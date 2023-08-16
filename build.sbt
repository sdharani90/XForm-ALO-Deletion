name := "XForm-ALO-Deletion"
organization := "com.cloudhealthtech"

/**
 * [AB-2000]: we'll now run X-form on EMR-6.10.0 which has a support for spark-3.3.1,
 * for more details, please refer to https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-6100-release.html
 */
val sparkVersion = "3.3.1"
val json4sVersion = "3.7.0-M11"
val rabbitVersion = "4.2.0"
val awsSDKVersion = "1.12.397"
val hadoopVersion = "3.3.3"
val jacksonVersion = "2.13.4"
val commonsLang3Version = "3.12.0"
val libJavaArtifact = "cht-lib-java-emr6.9"
val libJavaVersion = "1.0.8-SNAPSHOT"

val sparkDependencyScope = "provided"

val sparkDepsStandalone = Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-streaming" % sparkVersion,
  "org.apache.spark" %% "spark-hive" % sparkVersion
)

val sparkDepsEMR = sparkDepsStandalone.map(x => {
  x % sparkDependencyScope
})

//version := "1.2.6-SNAPSHOT" // AB-1176 - 1.2.6 in the branch legacy_mdr
version := "1.4.4-spark3.3.1-SNAPSHOT"
scalaVersion := "2.12.10"
scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")
fork := true
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
javaOptions ++= Seq("-Xmx2G")

resolvers ++= Seq("maven-cht-upstream" at "https://artifactory.mgmt.cloudhealthtech.com/artifactory/maven-cht-upstream")
resolvers += Resolver.mavenLocal

val buildContext = System.getProperty("emrbuild", "false").equals("true")

val sparkDependencies = if (buildContext) {
  sparkDepsEMR
} else {
  sparkDepsStandalone
}



lazy val primary = project.in(file(".")) // dependsOn RootProject(libScala)


libraryDependencies ++= sparkDependencies ++ Seq(
  "org.json4s" %% "json4s-native" % json4sVersion,
  "com.amazonaws" % "aws-java-sdk-s3" % awsSDKVersion, // lib-java requires 1.11.704 only for STS IAM creds builder
  "com.amazonaws" % "aws-java-sdk-ssm" % awsSDKVersion,
  "com.amazonaws" % "aws-java-sdk-dynamodb" % awsSDKVersion,
  "mysql" % "mysql-connector-java" % "5.1.43",
  // the new hadoop came with the bundled-sdk increasing the fat Jar's size, exclusionRule is to remove that
  "org.apache.hadoop" % "hadoop-aws" % hadoopVersion excludeAll ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-bundle"),
  "org.apache.hadoop" % "hadoop-common" % hadoopVersion,
  "org.apache.commons" % "commons-lang3" % commonsLang3Version,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
  "com.rabbitmq" % "amqp-client" % rabbitVersion,
  "org.apache.hbase" % "hbase-client" % "1.2.0",
  "org.apache.commons" % "commons-compress" % "1.16.1",
  "com.rollbar" % "rollbar-java" % "1.8.1",
  "com.datadoghq" % "java-dogstatsd-client" % "2.3",
  "com.cloudhealthtech" % libJavaArtifact % libJavaVersion,
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  "org.mockito" %% "mockito-scala-scalatest" % "1.17.12" % Test,
  "org.scalamock" %% "scalamock" % "5.2.0" % Test,
  "org.apache.kafka" %% "kafka" % "2.1.0",
  "com.h2database" % "h2" % "2.1.214" % Test,
  "io.github.embeddedkafka"%% "embedded-kafka"% "2.1.0" % Test,
  "software.amazon.awssdk" % "s3" % "2.20.117",
  "com.typesafe" % "config" % "1.4.2"

)

excludeDependencies ++= Seq("org.slf4j" % "slf4j-log4j12","log4j" % "log4j", "log4j" % "apache-log4j-extras")



// Publish to Artifactory
credentials += Credentials(Path.userHome / ".ivy2/creds")
publishMavenStyle := true
publishTo := Some("Artifactory Realm" at "https://artifactory.mgmt.cloudhealthtech.com/artifactory/maven-cht-upstream")

// define the statements initially evaluated when entering 'console', 'consoleQuick', or 'consoleProject'
// but still keep the console settings in the sbt-spark-package plugin

// If you want to use yarn-client for spark cluster mode, override the environment variable
// SPARK_MODE=yarn <cmd>
val sparkMode = sys.env.getOrElse("SPARK_MODE", "local[*]")

console / initialCommands :=
  s"""
     |import org.apache.spark.sql.SparkSession
     |
     |@transient val spark = SparkSession.builder().master("$sparkMode").appName("Console test").getOrCreate()
     |implicit def sc = spark.sparkContext
     |implicit def sqlContext = spark.sqlContext
     |import spark.implicits._
     |import org.apache.spark.sql.functions._
     |
     |def time[T](f: => T): T = {
     |  import System.{currentTimeMillis => now}
     |  val start = now
     |  try { f } finally { println("Elapsed: " + (now - start)/1000.0 + " s") }
     |}
     |
     |""".stripMargin

console / cleanupCommands :=
  s"""
     |spark.stop()
   """.stripMargin
