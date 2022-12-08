/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy.server.datafabric

import scala.collection.JavaConverters._
import scala.util.Random

import io.fabric8.kubernetes.api.model.ListOptions
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException

import org.apache.livy.{LivyConf, Logging}


case class K8SUserInfo(namespace: String,
                       userSecretName: String,
                       driverSA: Option[String],
                       executorSA: Option[String])

object K8SUserInfo extends Logging {
  private lazy val client = new KubernetesClientBuilder().build()

  def apply(namespace: String, livyConf: LivyConf): K8SUserInfo = {
    if (!livyConf.isRunningOnKubernetes()) {
      throw new RuntimeException("K8S info can't be created in non-k8s environment")
    }

    val secret = secretName(namespace, livyConf)
    val driverSA = driverSAName(namespace, livyConf)
    val executorSA = executorSAName(namespace, livyConf)

    info(s"NamespaceInfo namespace = $namespace; secret = $secret; " +
      s"driverSA: ${driverSA.getOrElse("null")}; executorSA: ${executorSA.getOrElse("null")}")
    new K8SUserInfo(namespace, secret, driverSA, executorSA)
  }

  private def secretName(userNamespace: String,
                         livyConf: LivyConf): String = {
    val secretNamePattern = livyConf.get(LivyConf.KUBERNETES_USER_SECRET_PATTERN)

    if (!secretNamePattern.contains("%s")) {
      return secretNamePattern
    }

    val secretName = String.format(secretNamePattern, userNamespace)
    val maxLength = 63 - "-volume".length
    if (secretName.length <= maxLength) {
      secretName
    } else {
      val randSuffix = "-" + Random.alphanumeric
        .filter(c => c.isLower || c.isDigit)
        .take(5)
        .mkString
      secretName.take(maxLength - randSuffix.length) + randSuffix
    }
  }

  private def driverSAName(userNamespace: String, livyConf: LivyConf): Option[String] = {
    Option(livyConf.get(LivyConf.KUBERNETES_DRIVER_SA_USER_PATTERN))
      .filter(_.nonEmpty)
      .map(pattern => String.format(pattern, userNamespace))
  }

  private def executorSAName(userNamespace: String, livyConf: LivyConf): Option[String] = {
    Option(livyConf.get(LivyConf.KUBERNETES_EXECUTOR_SA_USER_PATTERN))
      .filter(_.nonEmpty)
      .map(pattern => String.format(pattern, userNamespace))
  }
}
