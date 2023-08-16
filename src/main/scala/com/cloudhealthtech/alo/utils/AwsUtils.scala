package com.cloudhealthtech.alo.utils

import com.cloudhealthtech.java.aws.creds.DefaultCredentialBuilders
import com.cloudhealthtech.java.aws.AWSCredentialsBuilder

object AwsUtils {

  def buildCredentials(creds: String): AWSCredentialsBuilder = {
    val cb = new DefaultCredentialBuilders()
    val parts = creds.split(":")
    parts(0) match {
      case "vault" => cb.buildVaultEnvCredentials(parts(1))
      case x =>
        throw new IllegalArgumentException("Unknown credentials option: %s".format(x))
    }
  }
}
