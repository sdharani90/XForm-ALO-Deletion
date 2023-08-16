package com.cloudhealthtech.alo.utils

import com.amazonaws.AmazonClientException
import com.amazonaws.services.s3.model.{AmazonS3Exception, MultiObjectDeleteException}
import org.apache.http.HttpStatus
import org.apache.log4j.Logger

import scala.annotation.tailrec
import scala.collection.JavaConversions.collectionAsScalaIterable

class S3RetriesExhaustException(message: String, cause: Throwable) extends RuntimeException(message)

object RetryHelper {
  private val logger = Logger.getLogger("RetryHelper")

  /**
   * AB-301: S3 client auto-retry is now enabled so "some" S3 errors should now get retried.
   * But, for example, a socket exception during S3 streaming requires an explicit catch and retry.
   * You can add other exception messages here.
   *
   * This is currently limited to S3 I/O context.
   *
   * Separate from [ErrorHandler.retryableExceptions]
   */
  val retryableLocalExceptions: Seq[String] = Seq[String](
    "Connection reset", // SSLException which can cause S3 stream down/upload to fail and must be retried
    "Resetting to invalid mark", // AB-1086, AB-957: S3 upload parquet chunk
    "Read timed out" // javax.net.ssl.SSLException - https://rollbar.com/cloudhealthtech/team-abracadata/items/1856/
  )

  /**
   * list of server error-codes that should be retried
   * see [[https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html#ErrorCodeList]], specifically 500 ones
   */
  val retryableS3ErrorCodes: Seq[String] = Seq[String] (
    "InternalError",
    "ServiceUnavailable",
    "Service Unavailable",
    "503 Service Unavailable",
    "SlowDown",
    "Busy",
    "503 Slow Down",
    "Read timed out", // javax.net.ssl.SSLException - superasterisk RB 6385
    "RequestTimeout",
    "500 Internal Server Error",
    "Unable to execute HTTP request",  // AB-1628
    "Failed to connect to service endpoint"
  )

  /**
   * list of sdk client errors that can be retried, right now this is explicit to stream errors
   * that needs to be recomputed as stream might have been consumed earlier and subsequently failed
   * the upload
   */
  val retryableS3ClientErrors: Seq[String] = Seq[String](
    "Data read has a different length than the expected"
  )

  /**
   * @param t the base exception
   * @return if its the retryable throttled exception or not
   */
  def isExceptionThrottledAndRetryable(t: Throwable): Boolean = t match {
    case ex: MultiObjectDeleteException =>
      ex.getErrors
        .exists(de => retryableS3ErrorCodes.exists(ec => ec.equals(de.getCode)))
    case ex: AmazonS3Exception =>
      ex.getStatusCode == HttpStatus.SC_SERVICE_UNAVAILABLE ||
        retryableS3ErrorCodes.exists(retryableS3ErrorCode => retryableS3ErrorCode.equals(ex.getErrorCode))
    case ex: AmazonClientException =>
      retryableS3ErrorCodes.exists(ex.getMessage.contains(_))
    case _ => false
  }

  /**
   * Allows to explicitly retry a function block locally if the exception is considered retryable locally.
   *
   * @param n the retry count, used recursively, caller should pass in max retry, e.g. 3 or 5.
   * @param sleepInMillis the back-off time in millis before retrying the request again
   * @param attempt, starts at 0, this is indicator of how many times the function has been executed
   * @param skipRetry, default being false, this is safe-guard to turn off retries in case we ever need to
   * @param fn the function block to retry
   * @tparam T the result of the function block to retry
   * @return
   *
   * @see [[retryableLocalExceptions]]
   */
  @tailrec
  final def retry[T](n: Int,
                     attempt: Int = 0,
                     sleepInMillis: Int = 1000,
                     skipRetry: Boolean = false)(fn: => T): T = {
    util.Try {
      fn
    } match {
      case util.Success(x) => x
      case util.Failure(t) if n > 1 && !skipRetry =>
        val throttled = isExceptionThrottledAndRetryable(t)
        if (foundInFragments(t, retryableLocalExceptions) || throttled) {
          logger.warn(s"Retrying ${n - 1}: " + t)
          if (throttled) {
            val backOffTime = (attempt + 1) * sleepInMillis
            logger.warn(s"Current request encountered a s3 server exception but will be retried, " +
              s"backing off for $backOffTime ms before retrying again.")
            Thread.sleep(backOffTime)
          }
          retry(n - 1, attempt = attempt + 1, sleepInMillis, skipRetry)(fn)
        } else {
          logger.warn(s"Abort ${n - 1}: " + t)
          throw t
        }
      case util.Failure(e) =>
        e match {
          case ex: AmazonS3Exception =>
            if (!isExceptionThrottledAndRetryable(ex)) {
              throw e
            }
            var totalBackOff = 0
            (1 to attempt).foreach(i => totalBackOff = totalBackOff + (i * sleepInMillis))
            val msg = s"Exhausted retry and back-off after attempt = $attempt and total backoff = $totalBackOff."
            throw new S3RetriesExhaustException(msg, ex)
          case _ => throw e
        }
    }
  }

  @tailrec
  def foundInFragments(t: Throwable, fragments: Seq[String]): Boolean = {
    val m = t.getMessage
    if (m == null) {
      false
    } else {
      val any = fragments.find(p => m.indexOf(p) > -1)
      if (any.isDefined) {
        true
      } else {
        // search causes
        val c = t.getCause
        if (c == null) {
          false
        } else {
          foundInFragments(c, fragments)
        }
      }
    }
  }
}
