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

import java.io.IOException

import jakarta.servlet._
import jakarta.servlet.http.{HttpServletRequest, HttpServletRequestWrapper}

import org.apache.livy.Logging

class AIEAuthFilter extends Filter with Logging {

  override def init(filterConfig: FilterConfig): Unit = {
    // no-op
  }

  @throws[IOException]
  @throws[ServletException]
  override def doFilter(
                         request: ServletRequest,
                         response: ServletResponse,
                         chain: FilterChain): Unit = {

    val httpReq = request.asInstanceOf[HttpServletRequest]

    val isGetAllSessionsOrBatches = httpReq.getMethod == "GET" &&
      httpReq.getRequestURI != null &&
      httpReq.getRequestURI.matches(".*/(?:sessions|batches)/?")

    val forwardedUser = Option(httpReq.getHeader("X-Forwarded-User"))
      .map(_.trim)
      .filter(_.nonEmpty)

    if (isGetAllSessionsOrBatches && forwardedUser.isEmpty) {
      info("Allowing access to get all sessions/batches endpoint")
      chain.doFilter(httpReq, response)
      return
    }

    val projectNamespace = forwardedUser.getOrElse {
      throw new ServletException("Missing or empty X-Forwarded-User header")
    }

    info(s"Project namespace: $projectNamespace")

    val wrappedRequest = new HttpServletRequestWrapper(httpReq) {
      override def getRemoteUser: String = projectNamespace
    }

    chain.doFilter(wrappedRequest, response)
  }

  override def destroy(): Unit = {}
}
