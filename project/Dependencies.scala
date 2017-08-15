import sbt._

object Version {
  val akka       = "2.4.20"
  val akkaHttp   = "10.0.9"
  val mockito    = "1.10.19"
  val scala      = "2.11.11"
  val scalaTest  = "2.2.6"
}

object Library {
  val akkaCluster           = "com.typesafe.akka" %% "akka-cluster"                       % Version.akka
  val akkaHttp              = "com.typesafe.akka" %% "akka-http"                          % Version.akkaHttp
  val akkaStream            = "com.typesafe.akka" %% "akka-stream"                        % Version.akka
  val akkaDistributedData   = "com.typesafe.akka" %% "akka-distributed-data-experimental" % Version.akka
  val akkaTestkit           = "com.typesafe.akka" %% "akka-testkit"                       % Version.akka
  val akkaHttpTestkit       = "com.typesafe.akka" %% "akka-http-testkit"                  % Version.akkaHttp
  val mockitoAll            = "org.mockito"       %  "mockito-all"                        % Version.mockito
  val scalaTest             = "org.scalatest"     %% "scalatest"                          % Version.scalaTest
}
