package org.thp.thehive.services

import akka.actor.ActorRef
import com.softwaremill.tagging.@@
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.traversal.{Converter, Graph, Traversal}
import org.thp.scalligraph.{CreateError, EntityIdOrName, EntityName, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.dto.v1.InputCustomFieldValue
import org.thp.thehive.models._
import play.api.libs.json.{JsObject, JsValue, Json}

import java.util.{Map => JMap}
import scala.util.{Failure, Success, Try}

class CaseTemplateSrv(
    override val customFieldSrv: CustomFieldSrv,
    override val customFieldValueSrv: CustomFieldValueSrv,
    override val organisationSrv: OrganisationSrv,
    tagSrv: TagSrv,
    taskSrv: TaskSrv,
    auditSrv: AuditSrv,
    integrityCheckActor: => ActorRef @@ IntegrityCheckTag
) extends VertexSrv[CaseTemplate]
    with TheHiveOps {

  val caseTemplateTagSrv              = new EdgeSrv[CaseTemplateTag, CaseTemplate, Tag]
  val caseTemplateCustomFieldValueSrv = new EdgeSrv[CaseTemplateCustomFieldValue, CaseTemplate, CustomFieldValue]
  val caseTemplateOrganisationSrv     = new EdgeSrv[CaseTemplateOrganisation, CaseTemplate, Organisation]
  val caseTemplateTaskSrv             = new EdgeSrv[CaseTemplateTask, CaseTemplate, Task]

  override def getByName(name: String)(implicit graph: Graph): Traversal.V[CaseTemplate] =
    startTraversal.getByName(name)

  override def createEntity(e: CaseTemplate)(implicit graph: Graph, authContext: AuthContext): Try[CaseTemplate with Entity] = {
    integrityCheckActor ! EntityAdded("CaseTemplate")
    super.createEntity(e)
  }

  def create(
      caseTemplate: CaseTemplate,
      organisation: Organisation with Entity,
      tasks: Seq[Task],
      customFields: Seq[InputCustomFieldValue]
  )(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichCaseTemplate] =
    if (organisationSrv.get(organisation).caseTemplates.get(EntityName(caseTemplate.name)).exists)
      Failure(CreateError(s"""The case template "${caseTemplate.name}" already exists"""))
    else
      for {
        createdCaseTemplate <- createEntity(caseTemplate)
        _                   <- caseTemplateOrganisationSrv.create(CaseTemplateOrganisation(), createdCaseTemplate, organisation)
        createdTasks        <- tasks.toTry(createTask(createdCaseTemplate, _))
        _                   <- caseTemplate.tags.toTry(tagSrv.getOrCreate(_).flatMap(t => caseTemplateTagSrv.create(CaseTemplateTag(), createdCaseTemplate, t)))
        cfs                 <- customFields.toTry(createCustomField(createdCaseTemplate, _))
        richCaseTemplate = RichCaseTemplate(createdCaseTemplate, organisation.name, createdTasks, cfs)
        _ <- auditSrv.caseTemplate.create(createdCaseTemplate, richCaseTemplate.toJson)
      } yield richCaseTemplate

  def createTask(caseTemplate: CaseTemplate with Entity, task: Task)(implicit graph: Graph, authContext: AuthContext): Try[RichTask] =
    for {
      assignee <- task.assignee.map(u => organisationSrv.current.users(Permissions.manageTask).getByName(u).getOrFail("User")).flip
      richTask <- taskSrv.create(task.copy(relatedId = caseTemplate._id, organisationIds = Set(organisationSrv.currentId)), assignee)
      _        <- caseTemplateTaskSrv.create(CaseTemplateTask(), caseTemplate, richTask.task)
      _        <- auditSrv.taskInTemplate.create(richTask.task, caseTemplate, richTask.toJson)
    } yield richTask

  override def update(
      traversal: Traversal.V[CaseTemplate],
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(Traversal.V[CaseTemplate], JsObject)] =
    auditSrv.mergeAudits(super.update(traversal, propertyUpdaters)) {
      case (templateSteps, updatedFields) =>
        templateSteps
          .clone()
          .getOrFail("CaseTemplate")
          .flatMap(auditSrv.caseTemplate.update(_, updatedFields))
    }
  def updateTags(caseTemplate: CaseTemplate with Entity, tags: Set[String])(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[(Seq[Tag with Entity], Seq[Tag with Entity])] =
    for {
      tagsToAdd <- (tags -- caseTemplate.tags).toTry(tagSrv.getOrCreate)
      tagsToRemove = get(caseTemplate).tags.toSeq.filterNot(t => tags.contains(t.toString))
      _ <- tagsToAdd.toTry(caseTemplateTagSrv.create(CaseTemplateTag(), caseTemplate, _))
      _ = if (tags.nonEmpty) get(caseTemplate).outE[CaseTemplateTag].filter(_.otherV().hasId(tagsToRemove.map(_._id): _*)).remove()
      _ <- get(caseTemplate).update(_.tags, tags.toSeq).getOrFail("CaseTemplate")
      _ <- auditSrv.caseTemplate.update(caseTemplate, Json.obj("tags" -> tags))
    } yield (tagsToAdd, tagsToRemove)

  def addTags(caseTemplate: CaseTemplate with Entity, tags: Set[String])(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    updateTags(caseTemplate, tags ++ caseTemplate.tags).map(_ => ())

  def createCustomField(
      caseTemplate: CaseTemplate with Entity,
      customField: InputCustomFieldValue
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCustomField] =
    customFieldSrv
      .getOrFail(EntityIdOrName(customField.name.value))
      .flatMap(cf => createCustomField(caseTemplate, cf, customField.value, customField.order))

  def createCustomField(
      caseTemplate: CaseTemplate with Entity,
      customField: RichCustomField
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCustomField] =
    createCustomField(caseTemplate, customField.customField, customField.jsValue, customField.order)

  def createCustomField(
      caseTemplate: CaseTemplate with Entity,
      customField: CustomField with Entity,
      customFieldValue: JsValue,
      order: Option[Int]
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCustomField] =
    for {
      cfv <- customFieldValueSrv.createCustomField(caseTemplate, customField, customFieldValue, order)
      _   <- caseTemplateCustomFieldValueSrv.create(CaseTemplateCustomFieldValue(), caseTemplate, cfv)
    } yield RichCustomField(customField, cfv)

  def updateOrCreateCustomField(
      caseTemplate: CaseTemplate with Entity,
      customField: CustomField with Entity,
      value: JsValue,
      order: Option[Int]
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCustomField] =
    get(caseTemplate)
      .customFields
      .has(_.customFieldName, customField.name)
      .headOption
      .fold(createCustomField(caseTemplate, customField, value, order)) { cfv =>
        customFieldValueSrv
          .updateValue(cfv, customField.`type`, value, order)
          .map(RichCustomField(customField, _))
      }

}

trait CaseTemplateOpsNoDeps { ops: TheHiveOpsNoDeps =>
  implicit class CaseTemplateOpsNoDepsDefs(traversal: Traversal.V[CaseTemplate])
      extends EntityWithCustomFieldOpsNoDepsDefs[CaseTemplate, CaseTemplateCustomFieldValue](traversal, ops) {

    def get(idOrName: EntityIdOrName): Traversal.V[CaseTemplate] =
      idOrName.fold(traversal.getByIds(_), getByName)

    def getByName(name: String): Traversal.V[CaseTemplate] = traversal.has(_.name, name)

    def visible(implicit authContext: AuthContext): Traversal.V[CaseTemplate] =
      traversal.filter(_.organisation.current)

    def can(permission: Permission)(implicit authContext: AuthContext): Traversal.V[CaseTemplate] =
      if (authContext.permissions.contains(permission))
        traversal.filter(_.organisation.current)
      else
        traversal.empty

    def richCaseTemplate: Traversal[RichCaseTemplate, JMap[String, Any], Converter[RichCaseTemplate, JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.organisation.value(_.name))
            .by(_.tasks.richTaskWithoutActionRequired.fold)
            .by(_.richCustomFields.fold)
        )
        .domainMap {
          case (caseTemplate, organisation, tasks, customFields) =>
            RichCaseTemplate(
              caseTemplate,
              organisation,
              tasks,
              customFields
            )
        }

    def organisation: Traversal.V[Organisation] = traversal.out[CaseTemplateOrganisation].v[Organisation]

    def tasks: Traversal.V[Task] = traversal.out[CaseTemplateTask].v[Task]

    def tags: Traversal.V[Tag] = traversal.out[CaseTemplateTag].v[Tag]
  }

//  implicit class CaseTemplateCustomFieldsOpsDefs(traversal: Traversal.E[CaseTemplateCustomField])
//      extends CustomFieldValueOpsDefs[CaseTemplateCustomField](traversal)
}

trait CaseTemplateOps { ops: TheHiveOps =>
  protected val customFieldSrv: CustomFieldSrv

  implicit class CaseTemplateOpsDefs(traversal: Traversal.V[CaseTemplate])
      extends EntityWithCustomFieldOpsDefs[CaseTemplate, CaseTemplateCustomFieldValue](traversal, ops)
//    override protected val customFieldSrv: CustomFieldSrv = caseTemplateOps.customFieldSrv
//    override def selectCustomField(traversal: Traversal.V[CaseTemplate]): Traversal.E[CaseTemplateCustomField] =
//      traversal.outE[CaseTemplateCustomField]
//  }
}

class CaseTemplateIntegrityCheckOps(
    val db: Database,
    val service: CaseTemplateSrv,
    organisationSrv: OrganisationSrv
) extends IntegrityCheckOps[CaseTemplate]
    with TheHiveOpsNoDeps {
  override def findDuplicates(): Seq[Seq[CaseTemplate with Entity]] =
    db.roTransaction { implicit graph =>
      organisationSrv
        .startTraversal
        .flatMap(
          _.in[CaseTemplateOrganisation]
            .v[Organisation]
            .group(_.byValue(_.name), _.by(_._id.fold))
            .unfold
            .selectValues
            .where(_.localCount.is(P.gt(1)))
            .traversal
        )
        .domainMap(ids => service.getByIds(ids: _*).toSeq)
        .toSeq
    }

  override def resolve(entities: Seq[CaseTemplate with Entity])(implicit graph: Graph): Try[Unit] =
    entities match {
      case head :: tail =>
        tail.foreach(copyEdge(_, head, e => e.label() == "CaseCaseTemplate" || e.label() == "AlertCaseTemplate"))
        service.getByIds(tail.map(_._id): _*).remove()
        Success(())
      case _ => Success(())
    }

  override def globalCheck(): Map[String, Int] =
    db.tryTransaction { implicit graph =>
      Try {
        val orphanIds = service.startTraversal.filterNot(_.organisation)._id.toSeq
        if (orphanIds.nonEmpty) {
          logger.warn(s"Found ${orphanIds.length} caseTemplate orphan(s) (${orphanIds.mkString(",")})")
          service.getByIds(orphanIds: _*).remove()
        }
        Map("orphans" -> orphanIds.size)
      }
    }.getOrElse(Map("globalFailure" -> 1))
}
