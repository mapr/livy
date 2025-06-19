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


case class K8SUserInfo(username: String,
                       namespace: Option[String],
                       userSecretName: String,
                       driverSA: Option[String],
                       executorSA: Option[String])

object K8SUserInfo extends Logging {
  private lazy val client = new KubernetesClientBuilder().build()

  def apply(username: String, livyConf: LivyConf): K8SUserInfo = {
    if (!livyConf.isRunningOnKubernetes()) {
      throw new RuntimeException("K8S info can't be created in non-k8s environment")
    }

    val namespace = getUserNamespace(username, livyConf)
    val formattedUsername = namespace.getOrElse(username)
    val secret = secretName(username, namespace, livyConf)
    val driverSA = driverSAName(formattedUsername, livyConf)
    val executorSA = executorSAName(formattedUsername, livyConf)

    info(s"UserInfo for $username: namespace = ${namespace.getOrElse("null")}; secret = $secret; " +
      s"driverSA: ${driverSA.getOrElse("null")}; executorSA: ${executorSA.getOrElse("null")}")
    new K8SUserInfo(username, namespace, secret, driverSA, executorSA)
  }

  private def findNamespaceByLabel(labelSelector: String): Option[String] = {
    val listOptions = new ListOptions()
    listOptions.setLabelSelector(labelSelector)

    client.namespaces().list(listOptions).getItems.asScala.toList match {
      case Nil =>
        debug(s"Could not find user namespace by selector '$labelSelector'.")
        None
      case ns :: Nil =>
        val name = ns.getMetadata.getName
        debug(s"Found namespace by '$labelSelector' selector: $name.")
        Some(name)
      case _ =>
        warn(s"Found multiple user namespaces by selector '$labelSelector'.")
        None
    }
  }

  private def getUserNamespace(username: String, livyConf: LivyConf): Option[String] = {
    val ezProjectLabelSelector = "ezprojects.hpe.com/private=" + username
    val ezuaLabelSelector = "hpe-ezua/username=" + username

    val res = try {
      findNamespaceByLabel(ezProjectLabelSelector)
        .orElse(findNamespaceByLabel(ezuaLabelSelector))
        .orElse {
          warn("Could not find user namespace by label.")
          None
        }
    } catch {
      case _: KubernetesClientException =>
        warn("Failed to lookup Kubernetes namespaces.")
        None
    }

    res.orElse {
      val pattern = livyConf.get(LivyConf.KUBERNETES_NAMESPACE_USER_PATTERN)
      if (!pattern.isBlank) {
        val hello = String.format(pattern, username)
        info(s"Setting namespace based on '${LivyConf.KUBERNETES_NAMESPACE_USER_PATTERN.key}' " +
          s"value: $hello.")
        Option(hello)
      } else {
        warn(s"Neither namespace with label nor namespace pattern option is available, " +
          "falling back to value from sparkConf.")
        None
      }
    }
  }

  private def secretName(realUsername: String,
                         userNamespace: Option[String],
                         livyConf: LivyConf): String = {
    val formattedUsername = if (userNamespace.nonEmpty) {
      userNamespace.get
    } else {
      realUsername.replaceAll("\\.|\\_", "")
    }

    val secretNamePattern = livyConf.get(LivyConf.KUBERNETES_USER_SECRET_PATTERN)
    val secretName = String.format(secretNamePattern, formattedUsername)
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

  private def driverSAName(formattedUsername: String, livyConf: LivyConf): Option[String] = {
    Option(livyConf.get(LivyConf.KUBERNETES_DRIVER_SA_USER_PATTERN))
      .filter(_.nonEmpty)
      .map(pattern => String.format(pattern, formattedUsername))
  }

  private def executorSAName(formattedUsername: String, livyConf: LivyConf): Option[String] = {
    Option(livyConf.get(LivyConf.KUBERNETES_EXECUTOR_SA_USER_PATTERN))
      .filter(_.nonEmpty)
      .map(pattern => String.format(pattern, formattedUsername))
  }
}
