package org.thp.thehive

import org.apache.commons.io.FileUtils
import org.thp.scalligraph.AppBuilder
import org.thp.scalligraph.models.Database
import org.thp.thehive.services.{UserSrv => _}

import java.io.File
import java.nio.file.{Files, Paths}
import javax.inject.Provider
import scala.util.Try

object TestAppBuilderLock

trait TestAppBuilder {

  val databaseName: String = "default"

  def appConfigure: AppBuilder = ???
//    (new AppBuilder)
//      .bind[UserSrv, LocalUserSrv]
//      .bind[StorageSrv, LocalFileSystemStorageSrv]
//      .bindNamed[QueryExecutor, TheHiveQueryExecutor]("v0")
//      .multiBind[AuthSrvProvider](classOf[LocalPasswordAuthProvider], classOf[LocalKeyAuthProvider], classOf[HeaderAuthProvider])
//      .multiBind[NotifierProvider](classOf[AppendToFileProvider])
//      .multiBind[NotifierProvider](classOf[EmailerProvider])
//      .multiBind[TriggerProvider](classOf[LogInMyTaskProvider])
//      .multiBind[TriggerProvider](classOf[CaseCreatedProvider])
//      .multiBind[TriggerProvider](classOf[TaskAssignedProvider])
//      .multiBind[TriggerProvider](classOf[AlertCreatedProvider])
//      .bindToProvider[AuthSrv, MultiAuthSrvProvider]
//      .bindInstance[SingleInstance](new SingleInstance(true))
//      .multiBind[GenIntegrityCheckOps](
//        classOf[ProfileIntegrityCheckOps],
//        classOf[OrganisationIntegrityCheckOps],
//        classOf[TagIntegrityCheckOps],
//        classOf[UserIntegrityCheckOps],
//        classOf[ImpactStatusIntegrityCheckOps],
//        classOf[ResolutionStatusIntegrityCheckOps],
//        classOf[ObservableTypeIntegrityCheckOps],
//        classOf[CustomFieldIntegrityCheckOps],
//        classOf[CaseTemplateIntegrityCheckOps],
//        classOf[DataIntegrityCheckOps],
//        classOf[CaseIntegrityCheckOps],
//        classOf[AlertIntegrityCheckOps]
//      )
//      .bindActor[DummyActor]("config-actor")
//      .bindActor[DummyActor]("notification-actor")
//      .bindActor[DummyActor]("integrity-check-actor")
//      .bindActor[DummyActor]("flow-actor")
//      .addConfiguration("auth.providers = [{name:local},{name:key},{name:header, userHeader:user}]")
//      .addConfiguration("play.modules.disabled = [org.thp.scalligraph.ScalligraphModule, org.thp.thehive.TheHiveModule]")
//      .addConfiguration("play.mailer.mock = yes")
//      .addConfiguration("play.mailer.debug = yes")
//      .addConfiguration(s"storage.localfs.location = ${System.getProperty("user.dir")}/target/storage")
//      .bindEagerly[ClusterSetup]

  def testApp[A](body: AppBuilder => A): A = {
    val storageDirectory = Files.createTempDirectory(Paths.get("target"), "janusgraph-test-database").toFile
    val indexDirectory   = Files.createTempDirectory(Paths.get("target"), storageDirectory.getName).toFile
    TestAppBuilderLock.synchronized {
      if (!Files.exists(Paths.get(s"target/janusgraph-test-database-$databaseName"))) {
        val app = appConfigure
//          .addConfiguration(s"""
//                               |db {
//                               |  provider: janusgraph
//                               |  janusgraph {
//                               |    storage.backend: berkeleyje
//                               |    storage.directory: "target/janusgraph-test-database-$databaseName"
//                               |    berkeleyje.freeDisk: 2
//                               |    index.search {
//                               |      backend : lucene
//                               |      directory: target/janusgraph-test-database-$databaseName-idx
//                               |    }
//                               |  }
//                               |}
//                               |akka.cluster.jmx.multi-mbeans-in-same-jvm: on
//                               |""".stripMargin)
//          .bindToProvider[Database, JanusDatabaseProvider]

        app[DatabaseBuilder].build()(app[Database])
        app[Database].close()
      }
      FileUtils.copyDirectory(new File(s"target/janusgraph-test-database-$databaseName"), storageDirectory)
      FileUtils.copyDirectory(new File(s"target/janusgraph-test-database-$databaseName-idx"), indexDirectory)
    }
    val app = appConfigure
//      .bindToProvider[Database, JanusDatabaseProvider]
//      .addConfiguration(s"""
//                           |db {
//                           |  provider: janusgraph
//                           |  janusgraph {
//                           |    storage.backend: berkeleyje
//                           |    storage.directory: $storageDirectory
//                           |    berkeleyje.freeDisk: 2
//                           |    index.search {
//                           |      backend : lucene
//                           |      directory: $indexDirectory
//                           |    }
//                           |  }
//                           |}
//                           |""".stripMargin)

    try body(app)
    finally {
      Try(app[Database].close())
      FileUtils.deleteDirectory(storageDirectory)
    }
  }
}

class BasicDatabaseProvider(database: Database) extends Provider[Database] {
  override def get(): Database = database
}
