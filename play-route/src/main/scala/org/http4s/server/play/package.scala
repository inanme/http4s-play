package org.http4s.server

import org.http4s.Request

package object play {

  def unauthReqToAppContext[F[_]](
    rc: Request[F],
    region: Option[Region]
  ): RequestContext = new RequestContext

}
