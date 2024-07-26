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

  private def getUserNamespace(username: String, livyConf: LivyConf): Option[String] = {
    val selector = "hpe-ezua/username=" + username

    try {
      val listOptions = new ListOptions()
      listOptions.setLabelSelector(selector)

      val namespaces = client.namespaces().list(listOptions).getItems

      namespaces.size() match {
        case 0 =>
          warn(s"Could not find user namespace by selector '$selector'.")
        case 1 =>
          val ns = namespaces.get(0).getMetadata.getName
          info(s"Found namespace by '$selector' selector: $ns.")
          return Option(ns)
        case _ =>
          warn(s"Found multiple user namespaces by selector '$selector'.")
      }
    } catch {
      case _: KubernetesClientException =>
        warn("Failed to lookup Kubernetes namespaces.")
    }

    val pattern = livyConf.get(LivyConf.KUBERNETES_NAMESPACE_USER_PATTERN)
    if (!pattern.isBlank) {
      val ns = String.format(pattern, username)
      info(s"Setting namespace based on '${LivyConf.KUBERNETES_NAMESPACE_USER_PATTERN.key}' " +
        s"value: $ns.")
      return Option(ns)
    }

    warn(s"Neither namespace with '$selector' label nor namespace pattern option is available, " +
      "falling back to value from sparkConf.")
    Option(null)
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
    val maybeSecretName = String.format(secretNamePattern, formattedUsername)
    if (maybeSecretName.length < 63) {
      maybeSecretName
    } else {
      formattedUsername
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
