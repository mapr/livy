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

import java.io.BufferedInputStream

import org.scalatra.ScalatraServlet


class LoginPageServlet extends ScalatraServlet {
  private val LOGIN_PAGE_LOCATION = "/org/apache/livy/server/ui/static/datafabric/login.html"

  get("/") {
    contentType = "text/html"
    val content = getClass.getResourceAsStream(LOGIN_PAGE_LOCATION)
    new BufferedInputStream(content)
  }
}
