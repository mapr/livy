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

package org.apache.livy.server.ui

import scala.xml.Node

import org.scalatra.ScalatraServlet

import org.apache.livy.LivyConf

class UIServlet(val basePath: String, livyConf: LivyConf) extends ScalatraServlet {
  before() { contentType = "text/html" }

  private val aieUiEnabled = livyConf.getBoolean(LivyConf.UI_AIE_ENABLED)
  private val ownerPathHosts = livyConf.get(LivyConf.UI_OWNER_PATH_HOSTS)

  private def toSingleQuotedJsString(value: String): String = {
    "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
  }

  private trait Page {
    val name: String
    def getNavCrumbs: Seq[Node] = Seq.empty
  }
  private case class SimplePage(name: String) extends Page

  private case class AllSessionsPage(name: String = "Sessions") extends Page {
    override def getNavCrumbs: Seq[Node] =
      <li class="active nav-item"><a href="#" class="nav-link">Sessions</a></li>
  }
  private case class SessionPage(id: Int) extends Page {
    val name: String = "Session " + id
    override def getNavCrumbs: Seq[Node] = {
        <li class="nav-item"><a href={basePath + "/ui"} class="nav-link">Sessions</a></li> ++
          <li class="active nav-item"><a href="#" class="nav-link">{name}</a></li>
    }
  }
  private case class LogPage(sessionType: String, id: Int, ownerPath: String = "") extends Page {
    val sessionName: String = sessionType + " " + id
    val name: String = sessionName + " Log"
    override def getNavCrumbs: Seq[Node] = {
      val sessionLink = if (sessionType == "Session") {
        basePath + "/ui/" + ownerPath + "session/" + id
      } else {
        "#"
      }
      <li class="nav-item"><a href={basePath + "/ui"} class="nav-link">Sessions</a></li> ++
        <li class="nav-item"><a href={sessionLink} class="nav-link">{sessionName}</a></li> ++
        <li class="active nav-item"><a href="#" class="nav-link">Log</a></li>
    }
  }

  private def getHeader(pageName: String): Seq[Node] =
    <head>
      <link rel="stylesheet"
            href={basePath + "/static/css/bootstrap.min.css"}
            type="text/css"/>
      <link rel="stylesheet"
            href={basePath + "/static/css/dataTables.bootstrap.min.css"}
            type="text/css"/>
      <link rel="stylesheet" href={basePath + "/static/css/livy-ui.css"} type="text/css"/>
      <script src={basePath + "/static/js/jquery-3.5.1.min.js"}></script>
      <script src={basePath + "/static/js/bootstrap.bundle.min.js"}></script>
      <script src={basePath + "/static/js/jquery.dataTables.min.js"}></script>
      <script src={basePath + "/static/js/dataTables.bootstrap.min.js"}></script>
      <script src={basePath + "/static/js/livy-ui.js"}></script>
      <script src={basePath + "/static/datafabric/js/login.js"}></script>
      <script type="text/javascript">
        setBasePath({"'" + basePath + "'"});
        setAieUiEnabled({toSingleQuotedJsString(aieUiEnabled.toString)});
        setOwnerPathHosts({toSingleQuotedJsString(ownerPathHosts)});
      </script>
      <title>Livy - {pageName}</title>
    </head>

  private def wrapNavCrumbs(crumbs: Seq[Node]): Seq[Node] =
    <nav class="navbar navbar-light bg-light navbar-expand-md">
      <a class="navbar-brand" href={basePath + "/ui"}>
        <img alt="Livy" src={basePath + "/static/img/livy-mini-logo.png"} />
      </a>
      <div class="collapse navbar-collapse">
        <ul class="nav navbar-nav mr-auto">
          {crumbs}
        </ul>
        <button class="btn" onclick="logoutAction()">Logout</button>
      </div>
    </nav>

  private def createPage(pageInfo: Page, pageContents: Seq[Node]): Seq[Node] =
    <html>
      {getHeader(pageInfo.name)}
      <body>
        <div class="container-fluid">
          {getNavBar(pageInfo)}
          {pageContents}
        </div>
      </body>
    </html>

  private def getNavBar(page: Page): Seq[Node] = wrapNavCrumbs(page.getNavCrumbs)

  notFound {
    createPage(SimplePage("404"), <h3>404 No Such Page</h3>)
  }

  private def thriftSessionsTable: Seq[Node] = {
    if (livyConf.getBoolean(LivyConf.THRIFT_SERVER_ENABLED)) {
      <div id="thrift-sessions"></div> ++
        <script src={s"$basePath/static/js/thrift-sessions.js"}></script>
    } else {
      Seq.empty
    }
  }

  get("/") {
    val content =
      <div id="all-sessions">
        <div id="interactive-sessions"></div>
        <div id="batches"></div>
        {thriftSessionsTable}
        <script src={basePath + "/static/js/all-sessions.js"}></script>
      </div>

    createPage(AllSessionsPage(), content)
  }

  get("/:owner/?") {
    if (aieUiEnabled) {
      val content =
        <div id="all-sessions">
          <div id="interactive-sessions"></div>
          <div id="batches"></div>
          {thriftSessionsTable}
          <script src={basePath + "/static/js/all-sessions.js"}></script>
        </div>

      createPage(AllSessionsPage(), content)
    } else {
      notFound()
    }
  }

  get("/session/:id") {
    val content =
      <div id="session-page">
        <div id="session-summary"></div>
        <div id="session-statements"></div>
        <script src={basePath + "/static/js/session.js"}></script>
      </div>

    createPage(SessionPage(params("id").toInt), content)
  }

  // Owner is carried in the path (e.g. /ui/<owner>/session/:id) when accessed
  // through the Livy wrapper. The owner segment is informational for routing and
  // is ignored when rendering the page.
  get("/:owner/session/:id") {
    if (aieUiEnabled) {
      val content =
        <div id="session-page">
          <div id="session-summary"></div>
          <div id="session-statements"></div>
          <script src={basePath + "/static/js/session.js"}></script>
        </div>

      createPage(SessionPage(params("id").toInt), content)
    } else {
      notFound()
    }
  }

  private def getLogPage(page: LogPage): Seq[Node] = {
    val content =
      <div id="log-page">
        <div id="session-log"></div>
        <script src={basePath + "/static/js/session-log.js"}></script>
      </div>

    createPage(page, content)
  }

  get("/session/:id/log") {
    getLogPage(LogPage("Session", params("id").toInt))
  }

  get("/batch/:id/log") {
    getLogPage(LogPage("Batch", params("id").toInt))
  }

  get("/:owner/session/:id/log") {
    if (aieUiEnabled) {
      getLogPage(LogPage("Session", params("id").toInt, params("owner") + "/"))
    } else {
      notFound()
    }
  }

  get("/:owner/batch/:id/log") {
    if (aieUiEnabled) {
      getLogPage(LogPage("Batch", params("id").toInt, params("owner") + "/"))
    } else {
      notFound()
    }
  }
}
