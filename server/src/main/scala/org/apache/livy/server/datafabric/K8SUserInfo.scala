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
import io.fabric8.kubernetes.client.DefaultKubernetesClient

import org.apache.livy.{LivyConf, Logging}

case class K8SUserInfo(username: String,
                       defaultNamespace: Option[String],
                       userSecretName: String,
                       driverSA: Option[String],
                       executorSA: Option[String])

object K8SUserInfo extends Logging {
  private lazy val allNsK8SClient = new DefaultKubernetesClient().inAnyNamespace()

  def apply(username: String, livyConf: LivyConf): K8SUserInfo = {
    if (!livyConf.isRunningOnKubernetes()) {
      throw new RuntimeException("K8S info can't be created in non-k8s environment")
    }

    val namespace = getUserNamespace(username, livyConf)
    val formattedUsername = namespace.getOrElse(username)
    val secret = secretName(username, namespace, livyConf)
    val driverSA = driverSAName(formattedUsername, livyConf)
    val executorSA = executorSAName(formattedUsername, livyConf)

    info(s"UserInfo for $username: namespace = ${namespace.get}; secret = $secret; " +
      s"driverSA: ${driverSA.get}; executorSA: ${executorSA.get}")
    new K8SUserInfo(username, namespace, secret, driverSA, executorSA)
  }

  private def getUserNamespace(username: String, livyConf: LivyConf): Option[String] = {
    val selector = "hpe-ezua/username=" + username

    val listOptions = new ListOptions()
    listOptions.setLabelSelector(selector)
    val namespaces = allNsK8SClient.namespaces().list(listOptions).getItems

    val ns = namespaces.size() match {
      case 0 =>
        warn(s"Could not find user namespace by selector '$selector'. " +
          s"Trying to build namespace basing on ${LivyConf.KUBERNETES_NAMESPACE_USER_PATTERN.key}.")
        val pattern = livyConf.get(LivyConf.KUBERNETES_NAMESPACE_USER_PATTERN)
        if (!pattern.isBlank) {
          String.format(pattern, username)
        } else {
          warn("Namespace pattern is not set, falling back to value from sparkConf")
          null
        }
      case 1 =>
        namespaces.get(0).getMetadata.getName
      case _ =>
        warn(s"Found multiple user namespaces by selector '$selector', " +
          s"falling back to value from sparkConf")
        null
    }

    Option(ns)
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
