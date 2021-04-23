package org.thp.thehive.services

import akka.actor.ActorRef
import com.softwaremill.tagging.@@
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.services.{IntegrityCheckOps, VertexSrv}
import org.thp.scalligraph.traversal.{Graph, Traversal}
import org.thp.scalligraph.{CreateError, EntityIdOrName}
import org.thp.thehive.models.ResolutionStatus

import scala.util.{Failure, Success, Try}

class ResolutionStatusSrv(integrityCheckActor: => ActorRef @@ IntegrityCheckTag) extends VertexSrv[ResolutionStatus] with TheHiveOpsNoDeps {

  override def getByName(name: String)(implicit graph: Graph): Traversal.V[ResolutionStatus] =
    startTraversal.getByName(name)

  override def createEntity(e: ResolutionStatus)(implicit graph: Graph, authContext: AuthContext): Try[ResolutionStatus with Entity] = {
    integrityCheckActor ! EntityAdded("Resolution")
    super.createEntity(e)
  }

  def create(resolutionStatus: ResolutionStatus)(implicit graph: Graph, authContext: AuthContext): Try[ResolutionStatus with Entity] =
    if (exists(resolutionStatus))
      Failure(CreateError(s"Resolution status ${resolutionStatus.value} already exists"))
    else
      createEntity(resolutionStatus)

  override def exists(e: ResolutionStatus)(implicit graph: Graph): Boolean = startTraversal.getByName(e.value).exists
}

trait ResolutionStatusOps { _: TheHiveOpsNoDeps =>
  implicit class ResolutionStatusOpsDefs(traversal: Traversal.V[ResolutionStatus]) {
    def get(idOrName: EntityIdOrName): Traversal.V[ResolutionStatus] =
      idOrName.fold(traversal.getByIds(_), getByName)

    def getByName(name: String): Traversal.V[ResolutionStatus] = traversal.has(_.value, name)
  }
}

class ResolutionStatusIntegrityCheckOps(val db: Database, val service: ResolutionStatusSrv) extends IntegrityCheckOps[ResolutionStatus] {
  override def resolve(entities: Seq[ResolutionStatus with Entity])(implicit graph: Graph): Try[Unit] =
    entities match {
      case head :: tail =>
        tail.foreach(copyEdge(_, head))
        service.getByIds(tail.map(_._id): _*).remove()
        Success(())
      case _ => Success(())
    }

  override def globalCheck(): Map[String, Long] = Map.empty
}
