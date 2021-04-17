package org.thp.thehive.controllers.v1

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import ch.qos.logback.classic.{Level, LoggerContext}
import com.softwaremill.tagging.@@
import org.slf4j.LoggerFactory
import org.thp.scalligraph.controllers.Entrypoint
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.services.GenIntegrityCheckOps
import org.thp.thehive.models.Permissions
import org.thp.thehive.services._
import play.api.Logger
import play.api.libs.json.{JsObject, Json, OWrites}
import play.api.mvc.{Action, AnyContent, Results}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class AdminCtrl(
    entrypoint: Entrypoint,
    integrityCheckActor: => ActorRef @@ IntegrityCheckTag,
    integrityCheckOps: Set[GenIntegrityCheckOps],
    db: Database,
    implicit val ec: ExecutionContext
) {

  implicit val timeout: Timeout                      = Timeout(5.seconds)
  implicit val checkStatsWrites: OWrites[CheckStats] = Json.writes[CheckStats]
  implicit val checkStateWrites: OWrites[CheckState] = OWrites[CheckState] { state =>
    Json.obj(
      "needCheck"              -> state.needCheck,
      "duplicateTimer"         -> state.duplicateTimer.isDefined,
      "duplicateStats"         -> state.duplicateStats,
      "globalStats"            -> state.globalStats,
      "globalCheckRequestTime" -> state.globalCheckRequestTime
    )
  }
  lazy val logger: Logger = Logger(getClass)

  def setLogLevel(packageName: String, levelName: String): Action[AnyContent] =
    entrypoint("Update log level")
      .authPermitted(Permissions.managePlatform) { _ =>
        val level = levelName match {
          case "ALL"   => Level.ALL
          case "DEBUG" => Level.DEBUG
          case "INFO"  => Level.INFO
          case "WARN"  => Level.WARN
          case "ERROR" => Level.ERROR
          case "OFF"   => Level.OFF
          case "TRACE" => Level.TRACE
          case _       => Level.INFO
        }
        val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
        val logger        = loggerContext.getLogger(packageName)
        logger.setLevel(level)
        Success(Results.NoContent)
      }

  def triggerCheck(name: String): Action[AnyContent] =
    entrypoint("Trigger check")
      .authPermitted(Permissions.managePlatform) { _ =>
        integrityCheckActor ! GlobalCheckRequest(name)
        Success(Results.NoContent)
      }

  def checkStats: Action[AnyContent] =
    entrypoint("Get check stats")
      .asyncAuthPermitted(Permissions.managePlatform) { _ =>
        Future
          .traverse(integrityCheckOps.toSeq) { c =>
            (integrityCheckActor ? GetCheckStats(c.name))
              .mapTo[CheckState]
              .recover {
                case error =>
                  logger.error(s"Fail to get check stats of ${c.name}", error)
                  CheckState.empty
              }
              .map(c.name -> _)
          }
          .map { results =>
            Results.Ok(JsObject(results.map(r => r._1 -> Json.toJson(r._2))))
          }
      }

  val labels = Seq(
    "Config",
    "ReportTag",
    "KeyValue",
    "Pattern",
    "Case",
    "Procedure",
    "Alert",
    "Dashboard",
    "Observable",
    "User",
    "AnalyzerTemplate",
    "Taxonomy",
    "CustomField",
    "Data",
    "Organisation",
    "Profile",
    "Task",
    "Action",
    "Log",
    "CaseTemplate",
    "Audit",
    "Tag",
    "Job",
    "Attachment"
  )
  def indexStatus: Action[AnyContent] =
    entrypoint("Get index status")
      .authPermittedRoTransaction(db, Permissions.managePlatform) { _ => graph =>
        val indices = labels.map { label =>
          val count =
            try graph.indexCountQuery(s"""v."_label":$label""")
            catch {
              case error: Throwable =>
                logger.error("Index fetch error", error)
                0L
            }
          Json.obj("name" -> label, "count" -> count)

        }
        val indexCount = Json.obj("name" -> "global", "indices" -> indices)
        Success(Results.Ok(Json.obj("index" -> Seq(indexCount))))
      }

  def reindex(label: String): Action[AnyContent] =
    entrypoint("Reindex data")
      .authPermitted(Permissions.managePlatform) { _ =>
        Future(db.reindexData(label))
        Success(Results.NoContent)
      }
}
