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

import java.math.{BigDecimal => JBigDecimal}
import javax.servlet.ServletException

import io.fabric8.kubernetes.api.model.Quantity

import org.apache.livy.LivyConf
import org.apache.livy.server.interactive.InteractiveSession.info

class LimitsValidator(limit: BigDecimal) {
  def this(limit: JBigDecimal) = this(BigDecimal(limit))

  def this(limit: Quantity) = this(limit.getNumericalAmount)

  def this(limit: String) = this(new Quantity(
    if (limit == "") "0" else limit
  ))

  def this(limit: Int) = this(BigDecimal(limit))

  def validate(name: String, value: BigDecimal): LimitsValidator = {
    if (limit <= 0) {
      return this
    }
    if (value <= 0) {
      return this
    }
    if (value > limit) {
      throw new ServletException(
        s"Value ${value} of the ${name} parameter exceeds allowed limit of ${limit}.")
    }
    this
  }

  def validate(name: String, value: Quantity): LimitsValidator = {
    validate(name, value.getNumericalAmount)
  }

  def validate(name: String, value: String): LimitsValidator = {
    val safeValue = if (value == "") "0" else value
    validate(name, new Quantity(safeValue))
  }

  def validate(name: String, value: Int): LimitsValidator = {
    validate(name, BigDecimal(value))
  }

  def validate[T](name: String, value: Option[T]): LimitsValidator = {
    value match {
      case Some(stringOption: String) => validate(name, new Quantity(stringOption))
      case Some(intOption: Int) => validate(name, BigDecimal(intOption))
      case None => validate(name, 0)
      case _ => throw new ServletException(s"Invalid value for the property: ${name}")
    }
  }
}

object LimitsValidator {
  private val K = Math.pow(2, 10)
  private val M = Math.pow(2, 20)
  private val G = Math.pow(2, 30)
  private val T = Math.pow(2, 40)

  def parseJVMMemory(value: Option[String]): BigDecimal = {
    parseJVMMemory(value.getOrElse("0"))
  }

  def parseJVMMemory(value: String): BigDecimal = {
    val suffix = value.takeRight(1)
    val result = suffix match {
      case "k" | "K" => value.dropRight(1).toInt * K
      case "m" | "M" => value.dropRight(1).toInt * M
      case "g" | "G" => value.dropRight(1).toInt * G
      case "t" | "T" => value.dropRight(1).toInt * T
      // Doc for org.apache.spark.internal.config.DRIVER_MEMORY says
      // that this value is "in MiB unless otherwise specified":
      case _ => value.toInt * M
    }

    info(s"value: ${value}, result: ${result}")
    BigDecimal(result)
  }

  def validateEverything(
      livyConf: LivyConf,
      driverCores: Option[Int],
      driverMemory: Option[String],
      numExecutors: Option[Int],
      executorCores: Option[Int],
      executorMemory: Option[String],
      conf: Map[String, String]): Unit = {
    val limitDriverCores = livyConf.getInt(LivyConf.SESSION_RESOURCE_LIMITS_DRIVER_CORES)
    val limitDriverMemory = livyConf.get(LivyConf.SESSION_RESOURCE_LIMITS_DRIVER_MEMORY)
    val limitExecutorInstances = livyConf.getInt(
      LivyConf.SESSION_RESOURCE_LIMITS_EXECUTOR_INSTANCES)
    val limitExecutorCores = livyConf.getInt(LivyConf.SESSION_RESOURCE_LIMITS_EXECUTOR_CORES)
    val limitExecutorMemory = livyConf.get(LivyConf.SESSION_RESOURCE_LIMITS_EXECUTOR_MEMORY)

    new LimitsValidator(limitDriverCores)
      .validate("driverCores",
        driverCores)
      .validate("spark.driver.cores",
        conf.get("spark.driver.cores"))
      .validate("spark.kubernetes.driver.request.cores",
        conf.get("spark.kubernetes.driver.request.cores"))
      .validate("spark.kubernetes.driver.limit.cores",
        conf.get("spark.kubernetes.driver.limit.cores"))

    new LimitsValidator(parseJVMMemory(limitDriverMemory))
      .validate("driverMemory",
        parseJVMMemory(driverMemory))
      .validate("spark.driver.memory",
        parseJVMMemory(conf.get("spark.driver.memory")))
      .validate("spark.kubernetes.driver.request.memory",
        conf.get("spark.kubernetes.driver.request.memory"))
      .validate("spark.kubernetes.driver.limit.memory",
        conf.get("spark.kubernetes.driver.limit.memory"))

    new LimitsValidator(limitExecutorInstances)
      .validate("numExecutors",
        numExecutors)
      .validate("spark.executor.instances",
        conf.get("spark.executor.instances"))

    new LimitsValidator(limitExecutorCores)
      .validate("executorCores",
        executorCores)
      .validate("spark.executor.cores",
        conf.get("spark.executor.cores"))
      .validate("spark.kubernetes.executor.request.cores",
        conf.get("spark.kubernetes.executor.request.cores"))
      .validate("spark.kubernetes.executor.limit.cores",
        conf.get("spark.kubernetes.executor.limit.cores"))

    new LimitsValidator(LimitsValidator.parseJVMMemory(limitExecutorMemory))
      .validate("executorMemory",
        parseJVMMemory(executorMemory))
      .validate("spark.executor.memory",
        parseJVMMemory(conf.get("spark.executor.memory")))
      .validate("spark.kubernetes.executor.request.memory",
        conf.get("spark.kubernetes.executor.request.memory"))
      .validate("spark.kubernetes.executor.limit.memory",
        conf.get("spark.kubernetes.executor.limit.memory"))
  }
}
