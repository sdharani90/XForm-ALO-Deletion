package com.cloudhealthtech.alo.utils

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{AmazonS3Exception, DeleteObjectsRequest, MultiObjectDeleteException, ObjectListing, ObjectTagging, SetObjectTaggingRequest, Tag}
import com.cloudhealthtech.alo.utils.LCMUtils.log
import com.cloudhealthtech.alo.utils.RetryHelper.{isExceptionThrottledAndRetryable, retry}

import java.util
import scala.collection.JavaConversions._
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.mutable
import scala.annotation.tailrec

object CommonUtils {


  def noOfRetries: Int = System.getProperty("NO_OF_S3_RETRIES", "10").toInt

  def initialBackoff: Int = System.getProperty("INITIAL_S3_BACKOFF", "1000").toInt

  val skipRetry: Boolean = System.getProperty("SKIP_S3_RETRY", "false").toBoolean

  def getS3Objects(s3: AmazonS3,
                   bucket: String,
                   prefix: String): Array[String] = {

    val tags = new util.ArrayList[Tag]()
    tags.add(new Tag("deleteALO", "true"))

    val objectTagging = new ObjectTagging(tags)

    try {
      val result = retry(noOfRetries, sleepInMillis = initialBackoff, skipRetry = skipRetry) {
        recurseS3ObjectListing(s3, s3.listObjects(bucket, prefix), bucket, tags, objectTagging, Nil)
      }
      result.toArray
    } catch {
      case e: AmazonS3Exception =>
        e.printStackTrace()
        Array.empty
      // do nothing, return an empty array
    }
  }

  @tailrec
  def recurseS3ObjectListing(s3: AmazonS3, listing: ObjectListing, bucket: String, tags: util.ArrayList[Tag], objectTagging: ObjectTagging, keys: List[String] = Nil): List[String] = {

    val pageKeys = listing.getObjectSummaries.map(_.getKey)
      .filterNot(path => path.matches("xform/other/\\d+/asset-lifetime-override-asset-splitter/.*"))
      .map(x => {
        log.info(s"doing object tagging for $x ")
        s3.setObjectTagging(new SetObjectTaggingRequest(bucket, x, objectTagging))
        x
      })
      .toList

    if (listing.isTruncated)
      recurseS3ObjectListing(s3, s3.listNextBatchOfObjects(listing), bucket, tags, objectTagging, pageKeys ::: keys)
    else
      pageKeys ::: keys
  }

  def s3BatchSize: Int = System.getProperty("S3_BATCH_SIZE", "1000").toInt

  def deleteObjectsInBatches(s3: AmazonS3,
                             bucketName: String,
                             keys: List[String]): Unit = {
    // Partition the key versions list into sublists of size 1000
    val keyVersionsSublists = keys.grouped(s3BatchSize).toList
    log.info(s"deleting s3BatchSize of $s3BatchSize for keys")
    // Iterate over the sublists and delete objects in batches
    keyVersionsSublists.foreach { sublist =>
      val deleteRequest = new DeleteObjectsRequest(bucketName)
        .withKeys(sublist: _*) // Pass the sublist as variadic arguments
      CommonUtils.deleteObjects(s3, deleteRequest)
    }
  }

  def deleteObjects(s3: AmazonS3,
                    deleteObjectsRequest: DeleteObjectsRequest,
                    attempt: Int = 0): Unit = {
    try {
      s3.deleteObjects(deleteObjectsRequest)
    } catch {
      case ex: Exception =>
        if (attempt < noOfRetries && !skipRetry) {
          if (isExceptionThrottledAndRetryable(ex)) {
            Thread.sleep(initialBackoff * (attempt + 1))
          }
          deleteObjects(s3, deleteObjectsRequest, attempt + 1)
        } else {
          var totalBackOff = 0
          (1 to attempt).foreach(i => totalBackOff = totalBackOff + (i * initialBackoff))
          val msg = s"Exhausted retry and back-off after attempt = $attempt and total backoff = $totalBackOff."
          val details = ex match {
            case ex: MultiObjectDeleteException =>
              ex.getErrors.headOption
                .map(x => {
                  "[ Key = %s, Error-code = %s, Error = %s ]".format(x.getKey, x.getCode, x.getMessage)
                })
                .getOrElse("")
            case _ => ""
          }
          throw new S3RetriesExhaustException(msg + details, ex)
        }
    }
  }
}
