package db

import javax.inject.Inject
import anorm._
import io.flow.common.v0.models.UserReference
import io.flow.delta.actors.MainActor
import io.flow.delta.config.v0.models.{BuildConfig, Cluster}
import io.flow.delta.v0.models.{Build, Status}
import io.flow.postgresql.{Authorization, OrderBy, Query}
import io.flow.util.IdGenerator
import lib.BuildConfigUtil
import play.api.db._

class BuildsDao @Inject()(
  db: Database,
) {

  private[db] val Master = "master"

  private[this] val BaseQuery = Query(s"""
    select builds.id,
           builds.name,
           builds.status,
           builds.cluster,
           builds.project_id,
           projects.name as project_name,
           projects.uri as project_uri,
           projects.organization_id as project_organization_id
      from builds
      join projects on projects.id = builds.project_id
  """)

  def findAllByProjectId(auth: Authorization, projectId: String): Seq[Build] = {
    findAll(auth, projectId = Some(projectId), limit = None)
  }

  def findByProjectIdAndName(auth: Authorization, projectId: String, name: String): Option[Build] = {
    findAll(auth, projectId = Some(projectId), name = Some(name), limit = Some(1)).headOption
  }

  def findById(auth: Authorization, id: String): Option[Build] = {
    findAll(auth, ids = Some(Seq(id)), limit = Some(1)).headOption
  }

  def findAll(
    auth: Authorization,
    ids: Option[Seq[String]] = None,
    projectId: Option[String] = None,
    name: Option[String] = None,
    orderBy: OrderBy = OrderBy("lower(projects.name), lower(builds.name)"),
    limit: Option[Long],
    offset: Long = 0
  ): Seq[Build] = {

    db.withConnection { implicit c =>
      Standards.query(
        BaseQuery,
        tableName = "builds",
        auth = Filters(auth).organizations("projects.organization_id"),
        ids = ids,
        orderBy = orderBy.sql,
        limit = limit,
        offset = offset
      ).
        equals("builds.project_id", projectId).
        optionalText(
          "builds.name",
          name,
          columnFunctions = Seq(Query.Function.Lower),
          valueFunctions = Seq(Query.Function.Lower, Query.Function.Trim)
        ).
        as(
          io.flow.delta.v0.anorm.parsers.Build.parser().*
        )
    }
  }

}

case class BuildsWriteDao @javax.inject.Inject() (
                                                   imagesWriteDao: ImagesWriteDao,
                                                   buildsDao: BuildsDao,
                                                   buildDesiredStatesDao: BuildDesiredStatesDao,
                                                   buildLastStatesDao: InternalBuildLastStatesDao,
                                                   delete: Delete,
                                                   imagesDao: ImagesDao,
                                                   @javax.inject.Named("main-actor") mainActor: akka.actor.ActorRef,
                                                   db: Database
) {

  private[this] val UpsertQuery = """
    insert into builds
    (id, project_id, name, status, cluster, updated_by_user_id)
    values
    ({id}, {project_id}, {name}, {status}, {cluster}, {updated_by_user_id})
    on conflict(project_id, name)
    do update
          set status = {status},
              cluster = {cluster},
              updated_by_user_id = {updated_by_user_id}
  """

  private[this] val SelectBuildIdByProjectId = Query("""
    select id from builds where project_id = {project_id}
  """)

  private[this] val UpdateStatusQuery = """
    update builds
       set status = {status},
           updated_by_user_id = {updated_by_user_id}
     where id = {id}
  """

  private[this] val UpdateClusterQuery = """
    update builds
       set cluster = {cluster},
           updated_by_user_id = {updated_by_user_id}
     where id = {id}
  """

  private[this] val idGenerator = IdGenerator("bld")

  def upsert(createdBy: UserReference, projectId: String, status: Status, config: BuildConfig): Build = {
    db.withConnection { implicit c =>
      upsert(c, createdBy, projectId, status, config)
    }

    val configName = BuildConfigUtil.getName(config)
    val build = buildsDao.findByProjectIdAndName(Authorization.All, projectId, configName).getOrElse {
      sys.error(s"Failed to create build for projectId[$projectId] name[$configName]")
    }

    mainActor ! MainActor.Messages.BuildCreated(build.id)

    build
  }

  private[db] def upsert(implicit c: java.sql.Connection, createdBy: UserReference, projectId: String, status: Status, config: BuildConfig): Unit = {
    SQL(UpsertQuery).on(
      Symbol("id") ->idGenerator.randomId(),
      Symbol("project_id") ->projectId,
      Symbol("name") ->BuildConfigUtil.getName(config).trim,
      Symbol("status") ->status.toString,
      Symbol("cluster") ->BuildConfigUtil.getCluster(config).toString,
      Symbol("updated_by_user_id") ->createdBy.id
    ).execute()
    ()
  }

  def updateStatus(createdBy: UserReference, build: Build, status: Status): Build = {
    db.withConnection { implicit c =>
      SQL(UpdateStatusQuery).on(
        Symbol("id") ->build.id,
        Symbol("status") ->status.toString,
        Symbol("updated_by_user_id") ->createdBy.id
      ).execute()
    }

    mainActor ! MainActor.Messages.BuildUpdated(build.id)

    buildsDao.findById(Authorization.All, build.id).getOrElse {
      sys.error("Failed to update build")
    }
  }

  def updateCluster(createdBy: UserReference, buildId: String, cluster: Cluster): Unit = {
    db.withConnection { implicit c =>
      SQL(UpdateClusterQuery).on(
        Symbol("id") ->buildId,
        Symbol("cluster") ->cluster.toString,
        Symbol("updated_by_user_id") ->createdBy.id
      ).execute()
    }
    ()
  }

  def deleteAllByProjectId(user: UserReference, projectId: String): Unit = {
    db.withConnection { c =>
      SelectBuildIdByProjectId
        .bind("project_id", projectId)
        .as(SqlParser.str("id").*)(c)
    }.foreach { id =>
      deleteById(user, id)
    }
  }

  def delete(deletedBy: UserReference, build: Build): Unit = {
    deleteById(deletedBy, build.id)
  }

  private[this] def deleteById(deletedBy: UserReference, buildId: String): Unit = {
    imagesDao.findAll(buildId = Some(buildId), limit = None).foreach { image =>
      imagesWriteDao.delete(deletedBy, image)
    }

    buildDesiredStatesDao.deleteByBuildId(deletedBy, buildId)

    buildLastStatesDao.deleteByBuildId(deletedBy, buildId)

    delete.delete("builds", deletedBy.id, buildId)

    mainActor ! MainActor.Messages.BuildDeleted(buildId)
  }

}
