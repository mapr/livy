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

import scala.annotation.tailrec
import scala.util.parsing.json.JSON

import com.mapr.fs.proto.Security.ServerKeyType.ServerKey
import com.mapr.security.{MutableInt, Security}
import java.io.IOException
import java.io.UnsupportedEncodingException
import javax.servlet.ServletException
import org.apache.commons.codec.binary.Base64
import org.apache.http.client.ClientProtocolException
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils

import org.apache.livy.Logging

object CLDBUtils extends Logging {
  private val CLDB_HOSTS = sys.env("MAPR_CLDB_HOSTS")
    .split("(, +|,| +)").toList.filter(_.nonEmpty)
    .map(_.trim).map(_.split(":")(0))
  private val TICKET_TYPE = "SERVICEWITHIMPERSONATION"
  val MAPR_CLUSTER: String = sys.env("MAPR_CLUSTER")
  val SECURE_CLUSTER: String = sys.env("SECURE_CLUSTER")
    .stripLineEnd.trim.toLowerCase

  def getTicketAndKey: String = {
    val err = new MutableInt
    err.SetValue(0)
    val tk = Security.GetTicketAndKeyForCluster(ServerKey, MAPR_CLUSTER, err)
    if (err.GetValue != 0) {
      throw new ServletException("Can not generate TicketAndKey for Livy Server to access CLDB.")
    }
    val b64 = new Base64
    b64.encodeAsString(tk.toByteArray)
  }

  def requestGenTicket(cldbHost: String, username: String, ticketAndKeyString: String): String = {
    var response: String = null

    val requestBody = s"""{
        "class": "com.mapr.login.common.GenTicketTypeRequest",
        "targetUserName": "${username}",
        "ticketAndKeyString": "${ticketAndKeyString}",
        "ticketType": "${TICKET_TYPE}"
      }""".stripMargin

    val endpoint = s"https://${cldbHost}:7443/gentickettype"

    val request = new HttpPost(endpoint)
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(requestBody))

    try {
      debug(s"Trying to execute '${endpoint}' request.")
      val httpClientBuilder = HttpClients.custom
      httpClientBuilder.setSSLHostnameVerifier(new NoopHostnameVerifier)
      val httpClient = httpClientBuilder.build
      val httpResponse = httpClient.execute(request)

      response = EntityUtils.toString(httpResponse.getEntity)
    } catch {
      case e@(_: UnsupportedEncodingException | _: ClientProtocolException | _: IOException) =>
        debug(e)
    }
    response
  }

  def extractTicketFromGenTicketResponse(jsonResponse: String): String = {
    val tickJson = JSON.parseFull(jsonResponse).get.asInstanceOf[Map[String, Any]]
    tickJson.get("ticketAndKeyString").get.asInstanceOf[String]
  }

  def genUserTicket(username: String): String = {
    val cldbHosts = CLDB_HOSTS
    val ticketAndKey = getTicketAndKey

    @tailrec
    def retryCldbRequest(cldbHosts: List[String]): String = cldbHosts match {
      case Nil => null
      case cldbHost :: lastCldbs =>
        var response: String = null
        response = requestGenTicket(cldbHost, username, ticketAndKey)
        if (response != null) {
          debug(s"Got response with ticketAndKeyString from CLDB '${cldbHost}'.")
          return response
        }
        retryCldbRequest(lastCldbs)
    }

    val genTicketResponse = retryCldbRequest(cldbHosts)
    if (genTicketResponse == null) {
      throw new ServletException(
        s"Can not generate user ticket using any of CLDBs provided: '${cldbHosts.mkString(", ")}'.")
    }

    extractTicketFromGenTicketResponse(genTicketResponse)
  }

  def encodeTicketForWritingToFile(ticketAndKey: String): String = {
    val b64 = new Base64
    val ticketAndKeyB64 = b64.decode(ticketAndKey)
    val err = new MutableInt
    err.SetValue(0)
    val encodedTicket = Security.EncodeDataForWritingToKeyFile(ticketAndKeyB64, err)
    if (err.GetValue != 0) {
      throw new ServletException("Can not encode ticket for writing to file.")
    }
    new String(encodedTicket, "UTF-8")
  }

  def genUserTicketFile(username: String): String = {
    val ticket = genUserTicket(username)
    val encodedTicket = encodeTicketForWritingToFile(ticket)
    s"${MAPR_CLUSTER} ${encodedTicket}\n"
  }
}
