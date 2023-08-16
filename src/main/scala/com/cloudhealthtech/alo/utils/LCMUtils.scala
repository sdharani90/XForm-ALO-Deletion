package com.cloudhealthtech.alo.utils

import com.amazonaws.{AmazonServiceException, SdkClientException}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration.NoncurrentVersionExpiration
import com.amazonaws.services.s3.model.{BucketLifecycleConfiguration}
import com.amazonaws.services.s3.model.lifecycle.{LifecycleFilter, LifecyclePrefixPredicate}
import com.cloudhealthtech.alo.builder.BasicS3ClientBuilder
import com.cloudhealthtech.alo.utils.AwsUtils.buildCredentials
import org.apache.log4j.LogManager
import com.cloudhealthtech.java.aws.AWSCredentialsBuilder
import com.typesafe.config.ConfigFactory

import java.util
import scala.collection.convert.ImplicitConversions.{`buffer AsJavaList`, `collection AsScalaIterable`, `list asScalaBuffer`}
import scala.io.Source

object LCMUtils extends App {

  val log = LogManager.getRootLogger
  val config = ConfigFactory.load()

  val getCreds = config.getString("vault")

  val creds: AWSCredentialsBuilder = buildCredentials(getCreds)
  val s3b = new BasicS3ClientBuilder(creds)
  val s3Client: AmazonS3 = s3b.buildNewClient()

  val bucketName = config.getString("bucket_name")
  val commonPrefix = config.getString("prefix")

  val noncurrentExpiration = new NoncurrentVersionExpiration()
  noncurrentExpiration.setDays(1)

  val source = Source.fromFile("src/main/resources/customer_list.txt")
  val lines = source.getLines().toSeq


  println(lines.mkString(","))
  source.close()
  val customersList = new util.ArrayList[String]()
  lines.drop(1).foreach { cust => customersList.add(cust) }

  val customers = customersList.diff(List("2017", "47845"))
  log.info("customers " + customers.mkString(","))

  val handlers = List("asset-lifetime-override-assemble-with-existing",
    "asset-lifetime-override-assemble-newly-resolved-extended",
    "asset-lifetime-override-bounds-population",
    "asset-lifetime-override-extended-lifetime",
    "asset-lifetime-override-found-types",
    "asset-lifetime-override-known-assets",
    "asset-lifetime-override-lifetime-population",
    "asset-lifetime-override-mask-existing",
    "asset-lifetime-override-masked-lifetime-population",
    "asset-lifetime-override-never-resolved",
    "asset-lifetime-override-newly-resolved-first-time",
    "asset-lifetime-override-universal-asset-cache")


  val lifecycleRules = new util.ArrayList[BucketLifecycleConfiguration.Rule]()
  for (cust <- List(2017,47845)) {
    for (handler <- handlers){

        log.info(s"Creating life cycle rule for $cust and $handler")
        val prefixPredicate = new LifecyclePrefixPredicate(s"xform/other/${cust}/${handler}/")

        val filter = new LifecycleFilter(prefixPredicate)

        val rule: BucketLifecycleConfiguration.Rule = new BucketLifecycleConfiguration.Rule()

        rule.setId(s"Delete ALO for customer:${cust}:${handler}")
        rule.setFilter(filter)
        rule.setExpirationInDays(1)
        rule.setNoncurrentVersionExpiration(noncurrentExpiration)
        rule.setStatus(BucketLifecycleConfiguration.ENABLED)

      // Add the rules to a new BucketLifecycleConfiguration.
        lifecycleRules.add(rule)


    }
  }


  try {

    CommonUtils.getS3Objects(s3Client, bucketName, commonPrefix)
    val existingRules: util.List[BucketLifecycleConfiguration.Rule] = s3Client.getBucketLifecycleConfiguration(bucketName).getRules
    lifecycleRules.addAll(existingRules)
    var configuration: BucketLifecycleConfiguration = new BucketLifecycleConfiguration().withRules(lifecycleRules)
    s3Client.setBucketLifecycleConfiguration(bucketName, configuration)

    configuration = s3Client.getBucketLifecycleConfiguration(bucketName)
    System.out.println("found # of rules: " + configuration.getRules.size)

    val s = if (configuration == null) "No configuration found."
    else "Configuration found."
    System.out.println(s)
  } catch {
    case e: AmazonServiceException =>

      // The call was transmitted successfully, but Amazon S3 couldn't process
      // it, so it returned an error response.
      e.printStackTrace()
    case e: SdkClientException =>

      // Amazon S3 couldn't be contacted for a response, or the client
      // couldn't parse the response from Amazon S3.
      e.printStackTrace()
  }
}