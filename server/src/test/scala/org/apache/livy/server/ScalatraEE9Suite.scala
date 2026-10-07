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

import java.util.EnumSet

import jakarta.servlet.{DispatcherType, Filter}
import jakarta.servlet.http.HttpServlet
import org.eclipse.jetty.ee9.servlet.{DefaultServlet, FilterHolder, ServletContextHandler,
  ServletHolder}
import org.eclipse.jetty.server.{Server, ServerConnector}
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.matchers.should.Matchers
import org.scalatra.servlet.{HasMultipartConfig, ScalatraAsyncSupport}
import org.scalatra.test.{HttpComponentsClient, SessionSerializingListener}

/**
 * Replacement for scalatra's `org.scalatra.test.scalatest.ScalatraSuite`.
 *
 * scalatra-test's embedded Jetty container is compiled against Jetty 11 (or Jetty 12
 * ee10/ee11 in newer releases), which is binary incompatible with the Jetty 12 ee9
 * (jakarta.servlet 5) environment Livy runs on. This trait provides the same API backed
 * by an embedded Jetty 12 ee9 server.
 */
trait ScalatraEE9Suite extends Suite with HttpComponentsClient with BeforeAndAfterAll
  with Matchers {

  private val DefaultDispatcherTypes: EnumSet[DispatcherType] =
    EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC)

  /** Port to listen on. 0 means any available port. */
  def port: Int = 0

  def contextPath: String = "/"

  def skipDefaultServlet: Boolean = false

  lazy val server = new Server(port)

  lazy val servletContextHandler: ServletContextHandler = {
    val handler = new ServletContextHandler(ServletContextHandler.SESSIONS)
    handler.setContextPath(contextPath)
    handler
  }

  // Add a default servlet. If there is no underlying servlet, filters just return 404.
  if (!skipDefaultServlet) {
    servletContextHandler.addServlet(new ServletHolder("default", classOf[DefaultServlet]), "/")
  }

  def localPort: Option[Int] = server.getConnectors.collectFirst {
    case c: ServerConnector => c.getLocalPort
  }

  override def baseUrl: String =
    server.getConnectors.collectFirst {
      case conn: ServerConnector =>
        val host = Option(conn.getHost).getOrElse("localhost")
        val port = conn.getLocalPort
        require(port > 0, "The detected local port is < 1, that's not allowed")
        "http://%s:%d".format(host, port)
    }.getOrElse(sys.error("can't calculate base URL: no connector"))

  def mount(klass: Class[_], path: String): Unit = klass match {
    case servlet if classOf[HttpServlet].isAssignableFrom(servlet) =>
      addServlet(servlet.asInstanceOf[Class[_ <: HttpServlet]], path)
    case filter if classOf[Filter].isAssignableFrom(filter) =>
      addFilter(filter.asInstanceOf[Class[_ <: Filter]], path)
    case _ =>
      throw new IllegalArgumentException(
        klass.toString + " is not assignable to either HttpServlet or Filter")
  }

  def mount(servlet: HttpServlet, path: String): Unit = addServlet(servlet, path)

  def mount(servlet: HttpServlet, path: String, name: String): Unit =
    addServlet(servlet, path, name)

  def mount(app: Filter, path: String): FilterHolder = addFilter(app, path)

  def addServlet(servlet: HttpServlet, path: String): Unit =
    addServlet(servlet, path, servlet.getClass.getName)

  def addServlet(servlet: HttpServlet, path: String, name: String): Unit = {
    val holder = new ServletHolder(name, servlet)
    servlet match {
      case s: HasMultipartConfig =>
        holder.getRegistration.setMultipartConfig(s.multipartConfig.toMultipartConfigElement)
      case _: ScalatraAsyncSupport =>
        holder.getRegistration.setAsyncSupported(true)
      case _ =>
    }
    servletContextHandler.addServlet(holder, if (path.endsWith("/*")) path else path + "/*")
  }

  def addServlet(servlet: Class[_ <: HttpServlet], path: String): Unit =
    servletContextHandler.addServlet(servlet, path)

  def addFilter(
      filter: Filter,
      path: String,
      dispatches: EnumSet[DispatcherType] = DefaultDispatcherTypes): FilterHolder = {
    val holder = new FilterHolder(filter)
    servletContextHandler.addFilter(holder, path, dispatches)
    holder
  }

  def addFilter(filter: Class[_ <: Filter], path: String): FilterHolder =
    servletContextHandler.addFilter(filter, path, DefaultDispatcherTypes)

  protected def ensureSessionIsSerializable(): Unit =
    servletContextHandler.getSessionHandler.addEventListener(SessionSerializingListener)

  protected def start(): Unit = {
    server.setHandler(servletContextHandler)
    server.start()
  }

  protected def stop(): Unit = server.stop()

  override protected def beforeAll(): Unit = start()

  override protected def afterAll(): Unit = stop()
}
