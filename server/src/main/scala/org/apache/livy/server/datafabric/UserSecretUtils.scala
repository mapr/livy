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

import com.mapr.security.UnixUserGroupHelper
import io.fabric8.kubernetes.api.model.{ListOptions, Secret, SecretBuilder}
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import javax.servlet.ServletException
import org.apache.commons.codec.binary.Base64

import org.apache.livy.LivyConf
import org.apache.livy.Logging

class UserSecretUtils(var userInfo: K8SUserInfo,
                      val namespace: String,
                      val livyConf: LivyConf) extends Logging {
  private val k8sClient = (new DefaultKubernetesClient).inNamespace(namespace)
  val userSecretName: String = userInfo.userSecretName

  private def buildUserSecret: Secret = {
    val userLookupService = if (isEzProjectNamespace(namespace)) {
      "ezproject"
    } else {
      livyConf.get(LivyConf.KUBERNETES_USER_LOOKUP_SERVICE)
    }

    var uid: Option[Int] = null
    var gid: Option[Int] = null
    var groupName: Option[String] = null

    userLookupService match {
      case "nativesssd" => {
        val ugHelper = new UnixUserGroupHelper
        uid = Option(ugHelper.getUserId(userInfo.username))
        gid = Option(ugHelper.getGroups(userInfo.username)(0))
        groupName = Option(ugHelper.getGroupname(gid.get))
      }
      case "uaconfigmap" => {
        val userInfoCM = k8sClient.configMaps().withName("user-info-cm").get
        val userInfoData = userInfoCM.getData
        uid = Option(userInfoData.get("uid").toInt)
        gid = Option(userInfoData.get("gid").toInt)
        groupName = Option(userInfoData.getOrDefault("group", userInfo.username))
      }
      case "ezproject" => {
        val listOptions = new ListOptions()
        listOptions.setLabelSelector("ezprojects.hpe.com/resource=project-info")

        val configmaps = k8sClient.configMaps().list(listOptions).getItems.asScala.toList

        val projectInfoCM = configmaps match {
          case Nil =>
            throw new ServletException("Could not find 'project-info' ConfigMap.")
          case single :: Nil =>
            single.getData
          case _ =>
            throw new ServletException("Found multiple 'project-info' ConfigMaps.")
        }

        uid = Option(projectInfoCM.get("uid")).map(_.toInt)
        gid = Option(projectInfoCM.get("gid")).map(_.toInt)
        groupName = Option(projectInfoCM.getOrDefault("group", userInfo.username))
      }
      case _ => {
        throw new ServletException(
          s"Invalid value for ${LivyConf.KUBERNETES_USER_LOOKUP_SERVICE}: ${userLookupService}")
      }
    }

    val secureCluster = CLDBUtils.SECURE_CLUSTER

    val secretBuilder = new SecretBuilder()
      .withNewMetadata
      .withName(userSecretName)
      .addToLabels("created-by", "livy")
      .endMetadata

    val b64 = new Base64

    secretBuilder
      .addToData("MAPR_SPARK_UID", b64.encodeAsString(uid.get.toString.getBytes))
      .addToData("MAPR_SPARK_GID", b64.encodeAsString(gid.get.toString.getBytes))
      .addToData("MAPR_SPARK_USER", b64.encodeAsString(userInfo.username.getBytes))
      .addToData("MAPR_SPARK_GROUP", b64.encodeAsString(groupName.get.getBytes))
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

  private def isEzProjectNamespace(namespace: String): Boolean = {
    Option(k8sClient.namespaces.withName(namespace).get)
      .flatMap(ns => Option(ns.getMetadata))
      .flatMap(meta => Option(meta.getLabels))
      .exists(_.containsKey("ezprojects.hpe.com/ezproject"))
  }
}
