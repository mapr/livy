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

import io.fabric8.kubernetes.client.DefaultKubernetesClient

import org.apache.livy.Logging

class HistoryServerUtils(namespace: String) extends Logging {
  private val k8sClient = new DefaultKubernetesClient().inNamespace(namespace)

  def generateHistoryServerConfigs(): Map[String, Option[String]] = {
    val sparkHSCM = k8sClient.configMaps().withName("sparkhs-cm").get()
    if (sparkHSCM == null) {
      debug(s"HS settings were not found in target namespace '$namespace'. " +
        s"No HS settings will be added")
      return Map()
    }

    val hsStorageType = sparkHSCM.getData.get("storageKind")
    hsStorageType match {
      case "maprfs" =>
        debug("Using maprfs HS storage type")
        Map(
          "spark.eventLog.enabled" -> Option("true"),
          "spark.eventLog.dir" -> Option(s"maprfs:///apps/spark/$namespace")
        )
      case "pvc" =>
        val pvcName = sparkHSCM.getData.get("pvcName")
        val mountPath = "/var/log/sparkhs-eventlog-storage"
        debug(s"Using PVC '$pvcName' as HS storage")
        Map(
          "spark.eventLog.enabled" -> Option("true"),
          "spark.kubernetes.driver.volumes.persistentVolumeClaim." +
            "sparkhs-eventlog-storage.options.claimName" -> Option(pvcName),
          "spark.kubernetes.driver.volumes.persistentVolumeClaim." +
            "sparkhs-eventlog-storage.mount.path" -> Option(mountPath),
          "spark.kubernetes.driver.volumes.persistentVolumeClaim." +
            "sparkhs-eventlog-storage.mount.readOnly" -> Option("false"),
          "spark.eventLog.dir" -> Option(mountPath)
        )
      case _ =>
        warn(s"Unknown storage type $hsStorageType. No HS settings will be added")
        Map()
    }
  }
}
