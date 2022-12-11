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
import io.fabric8.kubernetes.api.model.{OwnerReferenceBuilder, Secret, SecretBuilder}
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import org.apache.commons.codec.binary.Base64

import org.apache.livy.LivyConf
import org.apache.livy.Logging

object UserSecretUtils extends Logging {
  private val INSTALLATION_NAME = sys.env("CHART_RELEASE_NAME")
}

class UserSecretUtils(val username: String, val livyConf: LivyConf) extends Logging {
  private val k8sClient = new DefaultKubernetesClient
  private val namespace = k8sClient.getNamespace
  private val secretNamePattern = livyConf.get(LivyConf.KUBERNETES_USER_SECRET_PATTERN)
  val userSecretName = String.format(secretNamePattern, username)

  private def buildUserSecret: Secret = {
    val secretBuilder = new SecretBuilder()
      .withNewMetadata
        .withName(userSecretName)
      .endMetadata

    // TODO: allow to configure which resource would be used as a secret owner.
    // We need to have fabric8io kubernetes-client of version 5.7+
    // to be able to use ResourceDefinitionContext to do this easily.
    val livyStatefulSet = k8sClient.apps.statefulSets
      .inNamespace(namespace)
      .withName(UserSecretUtils.INSTALLATION_NAME).get

    secretBuilder.editMetadata
      .withOwnerReferences(new OwnerReferenceBuilder()
        .withApiVersion(livyStatefulSet.getApiVersion)
        .withKind(livyStatefulSet.getKind)
        .withName(livyStatefulSet.getMetadata.getName)
        .withUid(livyStatefulSet.getMetadata.getUid)
        .build)
      .endMetadata

    val ugHelper = new UnixUserGroupHelper
    val uid = ugHelper.getUserId(username)
    val gid = ugHelper.getGroups(username)(0)
    val groupName = ugHelper.getGroupname(gid)
    val secureCluster = CLDBUtils.SECURE_CLUSTER

    val b64 = new Base64

    secretBuilder
      .addToData("MAPR_SPARK_UID", b64.encodeAsString(uid.toString.getBytes))
      .addToData("MAPR_SPARK_GID", b64.encodeAsString(gid.toString.getBytes))
      .addToData("MAPR_SPARK_USER", b64.encodeAsString(username.getBytes))
      .addToData("MAPR_SPARK_GROUP", b64.encodeAsString(groupName.getBytes))
      .addToData("SECURE_CLUSTER", b64.encodeAsString(secureCluster.getBytes))

    if (secureCluster == "true") {
      val ticketFile = CLDBUtils.genUserTicketFile(username)
      secretBuilder.addToData("CONTAINER_TICKET", b64.encodeAsString(ticketFile.getBytes))
    }

    secretBuilder.build
  }

  private def secretExist: Boolean = {
    k8sClient.secrets.inNamespace(namespace).withName(userSecretName).get != null
  }

  def ensureUserSecret: Boolean = {
    var result = false
    if (!secretExist) {
      val secret = buildUserSecret
      debug(s"""Creating user secret '${userSecretName}'
              | in namespace '${namespace}'
              | for user '${username}'.""".stripMargin)
      k8sClient.secrets.inNamespace(namespace).withName(userSecretName).create(secret)
      result = true
    }
    result
  }
}
