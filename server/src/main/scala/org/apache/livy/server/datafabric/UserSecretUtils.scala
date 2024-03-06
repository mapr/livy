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

import com.mapr.security.UnixUserGroupHelper
import io.fabric8.kubernetes.api.model.{Secret, SecretBuilder}
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import org.apache.commons.codec.binary.Base64

import org.apache.livy.LivyConf
import org.apache.livy.Logging

class UserSecretUtils(var userInfo: K8SUserInfo,
                      val namespace: String,
                      val livyConf: LivyConf) extends Logging {
  private val k8sClient = (new DefaultKubernetesClient).inNamespace(namespace)
  val userSecretName: String = userInfo.userSecretName

  private def buildUserSecret: Secret = {
    val secretBuilder = new SecretBuilder()
      .withNewMetadata
        .withName(userSecretName)
      .endMetadata

    val ugHelper = new UnixUserGroupHelper
    val uid = ugHelper.getUserId(userInfo.username)
    val gid = ugHelper.getGroups(userInfo.username)(0)
    val groupName = ugHelper.getGroupname(gid)
    val secureCluster = CLDBUtils.SECURE_CLUSTER

    val b64 = new Base64

    secretBuilder
      .addToData("MAPR_SPARK_UID", b64.encodeAsString(uid.toString.getBytes))
      .addToData("MAPR_SPARK_GID", b64.encodeAsString(gid.toString.getBytes))
      .addToData("MAPR_SPARK_USER", b64.encodeAsString(userInfo.username.getBytes))
      .addToData("MAPR_SPARK_GROUP", b64.encodeAsString(groupName.getBytes))
      .addToData("SECURE_CLUSTER", b64.encodeAsString(secureCluster.getBytes))

    if (secureCluster == "true") {
      val ticketFile = CLDBUtils.genUserTicketFile(userInfo.username)
      secretBuilder.addToData("CONTAINER_TICKET", b64.encodeAsString(ticketFile.getBytes))
    }

    secretBuilder.build
  }

  private def secretExist: Boolean = {
    k8sClient.secrets.withName(userSecretName).get != null
  }

  def ensureUserSecret(): Unit = {
    if (!secretExist) {
      val secret = buildUserSecret
      debug(s"""Creating user secret '${userSecretName}'
              | in namespace '${namespace}'
              | for user '${userInfo.username}'.""".stripMargin.replaceAll("\n", ""))
      k8sClient.secrets.inNamespace(namespace).withName(userSecretName).create(secret)
      info(s"Secret '${userSecretName}' for user '${userInfo.username}' has been created.")
    } else {
      info(s"Secret '${userSecretName}' for user '${userInfo.username}' already exists.")
    }
  }
}
