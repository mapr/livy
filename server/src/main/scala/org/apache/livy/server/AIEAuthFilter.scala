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
import jakarta.servlet.http.{HttpServletRequest, HttpServletRequestWrapper, HttpServletResponse}

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
    val uri = httpReq.getRequestURI

    val forwardedUser = Option(httpReq.getHeader("X-Forwarded-User"))
      .map(_.trim)
      .filter(_.nonEmpty)

    val isUIEndpoint = uri != null && uri.matches("^/ui(?:/.*)?$")
    val isStaticResource = uri != null && uri.matches("/static/.*")
    val isLogoutRequest = Option(httpReq.getParameter("action"))
      .map(_.trim)
      .exists(_.equalsIgnoreCase("logout"))
    val isGetAllSessionsOrBatches = httpReq.getMethod == "GET" &&
      uri != null && uri.matches(".*/(?:sessions|batches)/?")

    if (isUIEndpoint || isStaticResource) {
      info("Allowing access to UI endpoint")
      chain.doFilter(httpReq, response)
    } else if (isLogoutRequest && forwardedUser.isEmpty) {
      info("Allowing logout request without X-Forwarded-User header")
      chain.doFilter(httpReq, response)
    } else if (isGetAllSessionsOrBatches && forwardedUser.isEmpty) {
      info("Allowing access to get all sessions/batches endpoint")
      chain.doFilter(httpReq, response)
    } else {
      forwardedUser match {
        case Some(projectNamespace) =>
          info(s"Project namespace: $projectNamespace")

          val wrappedRequest = new HttpServletRequestWrapper(httpReq) {
            override def getRemoteUser: String = projectNamespace
          }
          chain.doFilter(wrappedRequest, response)
        case None =>
          error("Missing or empty X-Forwarded-User header for authorization.")
          val httpResp = response.asInstanceOf[HttpServletResponse]
          httpResp.sendError(HttpServletResponse.SC_UNAUTHORIZED,
            "Required headers for authorization are missing.")
      }
    }
  }

  override def destroy(): Unit = {}
}
