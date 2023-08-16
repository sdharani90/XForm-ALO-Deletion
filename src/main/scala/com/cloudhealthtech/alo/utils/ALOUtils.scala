package com.cloudhealthtech.alo.utils

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.DeleteObjectsRequest
import com.cloudhealthtech.alo.builder.BasicS3ClientBuilder
import com.cloudhealthtech.alo.utils.AwsUtils.buildCredentials
import com.cloudhealthtech.java.aws.AWSCredentialsBuilder
import com.typesafe.config.ConfigFactory
import org.apache.log4j.LogManager

import java.io.{BufferedWriter, File, FileOutputStream, FileWriter, PrintWriter}
import java.util
import scala.collection.convert.ImplicitConversions.{`buffer AsJavaList`, `collection AsScalaIterable`, `list asScalaBuffer`}
import scala.collection.mutable
import scala.io.Source

object ALOUtils extends App {

  val log = LogManager.getRootLogger

  val config = ConfigFactory.load()

  val getCreds = config.getString("vault")

  val creds: AWSCredentialsBuilder = buildCredentials(getCreds)
  val s3b = new BasicS3ClientBuilder(creds)
  val s3Client: AmazonS3 = s3b.buildNewClient()

  val bucketName = config.getString("bucket_name")
  val commonPrefix = config.getString("prefix")


  val source = Source.fromFile("src/main/resources/customer_list.txt")
  val lines = source.getLines().toSeq

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

  val lst = mutable.ListBuffer[String]()
  lines.drop(1).foreach { cust => handlers.foreach{ handler =>
    val path = s"s3://cht-faster-cubes/xform/other/${cust}/${handler}/"
    lst.add(path)
    }
  }

  //delete s3 objects
  CommonUtils.deleteObjectsInBatches(s3Client, bucketName, lst.toList)


}
