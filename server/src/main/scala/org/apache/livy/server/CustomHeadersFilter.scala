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

package org.apache.livy.server

import java.io._
import java.util.Properties
import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import scala.collection.JavaConverters._

private[livy] class CustomHeadersFilter(headersFileLocation: String) extends Filter {
  private val customHeadersProps = new Properties

  @throws(classOf[ServletException])
  override def init(filterConfig: FilterConfig) {
    val headersFile = new File(headersFileLocation)
    if (headersFile.exists) {
      try {
        customHeadersProps.loadFromXML(new FileInputStream(headersFile))
      } catch {
        case e: IOException => throw new ServletException(e)
      }
    } else {
      throw new ServletException(new FileNotFoundException(
        s"Headers file does not exist: $headersFileLocation"))
    }
  }

  override def doFilter(request: ServletRequest,
                        response: ServletResponse,
                        chain: FilterChain): Unit = {
    val httpResponse = response.asInstanceOf[HttpServletResponse]
    customHeadersProps.asScala.foreach {
      case (k, v) => httpResponse.addHeader(k.toString, v.toString)
    }
    chain.doFilter(request, response)
  }

  override def destroy(): Unit = {}
}
