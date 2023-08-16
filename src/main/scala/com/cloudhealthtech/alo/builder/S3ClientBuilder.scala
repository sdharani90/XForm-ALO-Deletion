package com.cloudhealthtech.alo.builder

import com.amazonaws.{ClientConfiguration, PredefinedClientConfigurations}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.cloudhealthtech.java.aws.AWSCredentialsBuilder

import scala.util.Properties

trait S3ClientBuilder extends Serializable {
  def region: String = {
    val regionKey = "AWS_REGION"
    Properties.propOrElse(regionKey,
      Properties.envOrElse(regionKey, "us-east-1"))
  }

  def buildNewClient() : AmazonS3

  def describe() : String = {
    "S3ClientBuilder/%s".format(this.getClass.getSimpleName)
  }

}

class BasicS3ClientBuilder(creds: AWSCredentialsBuilder) extends S3ClientBuilder {

  override def buildNewClient(): AmazonS3 = {
    AmazonS3ClientBuilder.standard()
      .withCredentials(creds.buildCredentialsProvider())
      .withRegion(region)
      .withClientConfiguration(defaultConfiguration)
      .build()
  }

  override def describe(): String = {
    "S3ClientBuilder/%s".format(creds.getClass.getSimpleName)
  }

  def defaultConfiguration: ClientConfiguration = {
    val conf = PredefinedClientConfigurations.defaultConfig()
    conf.setMaxErrorRetry(10)
    conf
  }

}
