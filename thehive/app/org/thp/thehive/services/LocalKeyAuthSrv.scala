package org.thp.thehive.services

import org.thp.scalligraph.auth.{UserSrv => _, _}
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.{EntityIdOrName, NotFoundError}
import play.api.Configuration
import play.api.mvc.RequestHeader

import java.util.Base64
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Random, Success, Try}

class LocalKeyAuthSrv(
    db: Database,
    userSrv: UserSrv,
    localUserSrv: LocalUserSrv,
    authSrv: AuthSrv,
    requestOrganisation: RequestOrganisation,
    ec: ExecutionContext
) extends KeyAuthSrv(authSrv, requestOrganisation, ec)
    with TheHiveOpsNoDeps {

  final protected def generateKey(): String = {
    val bytes = Array.ofDim[Byte](24)
    Random.nextBytes(bytes)
    Base64.getEncoder.encodeToString(bytes)
  }

  override val capabilities: Set[AuthCapability.Value] = Set(AuthCapability.authByKey)

  override def authenticate(key: String, organisation: Option[EntityIdOrName])(implicit request: RequestHeader): Try[AuthContext] =
    db.roTransaction { implicit graph =>
      userSrv
        .startTraversal
        .getByAPIKey(key)
        .getOrFail("User")
        .flatMap(user => localUserSrv.getAuthContext(request, user.login, organisation))
    }

  override def renewKey(username: String)(implicit authContext: AuthContext): Try[String] =
    db.tryTransaction { implicit graph =>
      val newKey = generateKey()
      userSrv
        .get(EntityIdOrName(username))
        .update(_.apikey, Some(newKey))
        .domainMap(_ => newKey)
        .getOrFail("User")
    }

  override def getKey(username: String)(implicit authContext: AuthContext): Try[String] =
    db.roTransaction { implicit graph =>
      userSrv
        .getOrFail(EntityIdOrName(username))
        .flatMap(_.apikey.fold[Try[String]](Failure(NotFoundError(s"User $username hasn't key")))(Success.apply))
    }

  override def removeKey(username: String)(implicit authContext: AuthContext): Try[Unit] =
    db.tryTransaction { implicit graph =>
      userSrv
        .get(EntityIdOrName(username))
        .update(_.apikey, None)
        .domainMap(_ => ())
        .getOrFail("User")
    }
}

class LocalKeyAuthProvider(
    db: Database,
    userSrv: UserSrv,
    localUserSrv: LocalUserSrv,
    _authSrv: => AuthSrv,
    requestOrganisation: RequestOrganisation,
    ec: ExecutionContext
) extends AuthSrvProvider {
  lazy val authSrv: AuthSrv = _authSrv
  override val name: String = "key"
  override def apply(config: Configuration): Try[AuthSrv] =
    Success(new LocalKeyAuthSrv(db, userSrv, localUserSrv, authSrv, requestOrganisation, ec))
}
