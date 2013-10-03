// -*- mode: Scala;-*- 
// Filename:    EvalHandler.scala 
// Authors:     lgm                                                    
// Creation:    Wed May 15 13:53:55 2013 
// Copyright:   Not supplied 
// Description: 
// ------------------------------------------------------------------------

package com.biosimilarity.evaluator.spray

import com.protegra_ati.agentservices.store._

import com.biosimilarity.evaluator.distribution._
import com.biosimilarity.evaluator.msgs._
import com.biosimilarity.lift.model.store._
import com.biosimilarity.lift.lib._

import akka.actor._
import spray.routing._
import directives.CompletionMagnet
import spray.http._
import spray.http.StatusCodes._
import MediaTypes._

import spray.httpx.encoding._

import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.JsonDSL._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.continuations._ 
import scala.collection.mutable.HashMap

import com.typesafe.config._

import javax.crypto._
import javax.crypto.spec.SecretKeySpec
import java.security._

import java.util.Date
import java.util.UUID

import java.net.URI

object CompletionMapper {
  @transient
  val map = new HashMap[String, RequestContext]()
  def complete(key: String, message: String): Unit = {
    for (reqCtx <- map.get(key)) {
      reqCtx.complete(HttpResponse(200, message))
    }
    map -= key
  }
}

object CometActorMapper {
  @transient
  val map = new HashMap[String, akka.actor.ActorRef]()
  def cometMessage(key: String, sessionURI: String, jsonBody: String): Unit = {
    for (cometActor <- map.get(key)) {
      cometActor ! CometMessage(sessionURI, HttpBody(`application/json`, jsonBody))
    }
    map -= key
  }
}

object ConfirmationEmail {
  def confirm(email: String, token: String) = {
    import org.apache.commons.mail._
    val simple = new SimpleEmail()
    simple.setHostName("smtp.googlemail.com")
    simple.setSmtpPort(465)
    simple.setAuthenticator(new DefaultAuthenticator("individualagenttech", "4genttech"))
    simple.setSSLOnConnect(true)
    simple.setFrom("individualagenttech@gmail.com")
    simple.setSubject("Confirm individual agent signup")
    // TODO(mike): get the URL from a config file
    simple.setMsg("""Please click on the following link to confirm that you'd like to create a new individual agent:
      http://64.27.3.17:6080/agentui.html?demo=false&token=""" + token)
    simple.addTo(email)
    simple.send()
  }
}

trait EvalHandler {
  self : EvaluationCommsService =>
 
  import DSLCommLink.mTT
  import ConcreteHL._
  
  @transient
  implicit val formats = DefaultFormats

  // Setup
  val userPWDBLabel = fromTermString("""pwdb(Salt, Hash, "user", K)""").
    getOrElse(throw new Exception("Couldn't parse label."))
  val adminPWDBLabel = fromTermString("""pwdb(Salt, Hash, "admin", K)""").
    getOrElse(throw new Exception("Couldn't parse label."))

  def toHex(bytes: Array[Byte]): String = {
    bytes.map("%02X" format _).mkString
  }

  def createAgentRequest(json: JValue, key: String): Unit = {
    try {
      var authType = (json \ "content" \ "authType").extract[String].toLowerCase
      if (authType != "password") {
        createAgentError(key, "Only password authentication is currently supported.")
      } else {
        val authValue = (json \ "content" \ "authValue").extract[String]
        val (salt, hash) = saltAndHash(authValue)

        // TODO(mike): explicitly manage randomness pool
        val rand = new SecureRandom()
        val bytes = new Array[Byte](16)
        
        // Generate random Agent URI
        rand.nextBytes(bytes)
        val uri = new URI("agent://" + toHex(bytes))
        val agentIdCnxn = PortableAgentCnxn(uri, "identity", uri)
        
        // Generate K for encrypting the lists of aliases, external identities, etc. on the Agent
        // term = pwdb(<salt>, hash = SHA(salt + pw), "user", AES_hash(K)) 
        // post term.toString on (Agent, term)
        {
          // Since we're encrypting exactly 128 bits, ECB is OK
          val aes = Cipher.getInstance("AES/ECB/NoPadding")
          aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hash, "AES"))
          // Generate K
          rand.nextBytes(bytes)
          // AES_hash(K)
          val aesHashK = toHex(aes.doFinal(bytes))
        
          val (erql, erspl) = agentMgr().makePolarizedPair()
          agentMgr().post(erql, erspl)(
            userPWDBLabel,
            List(agentIdCnxn),
            // TODO(mike): do proper context-aware interpolation
            "pwdb(" + List(salt, toHex(hash), "user", aesHashK).map('"'+_+'"').mkString(",") + ")",
            (optRsrc: Option[mTT.Resource]) => {
              CompletionMapper.complete(key, compact(render(
                ("msgType" -> "createAgentResponse") ~
                ("content" -> (
                  "agentURI" -> uri.toString
                ))
              )))
            }
          )
        }
      }
    } catch {
      case e: Exception => {
        createAgentError(key, e.toString)
      }
    }
  }
  def createAgentError(key: String, reason: String): Unit = {
    CompletionMapper.complete(key, compact(render(
      ("msgType" -> "createAgentError") ~
      ("content" -> (
        "reason" -> reason
      ))
    )))
  }
  def saltAndHash(pw: String): (String, Array[Byte]) = {
    val md = MessageDigest.getInstance("SHA1")
    val salt = UUID.randomUUID.toString.substring(0,8)
    md.update(salt.getBytes("utf-8"))
    md.update(pw.getBytes("utf-8"))
    (salt, md.digest)
  }

  // Agents
  def addAgentExternalIdentityRequest(json: JValue, key: String): Unit = {}
  def addAgentExternalIdentityToken(json: JValue, key: String): Unit = {}
  def removeAgentExternalIdentitiesRequest(json: JValue, key: String): Unit = {}
  def getAgentExternalIdentitiesRequest(json: JValue, key: String): Unit = {}
  def addAgentAliasesRequest(json: JValue, key: String): Unit = {
    @transient
    object handler extends EvalConfig
      with DSLCommLinkConfiguration
      with EvaluationCommsService
      with AgentCRUDHandler
      with Serializable {}
    handler.handleaddAgentAliasesRequest(
      key,
      com.biosimilarity.evaluator.msgs.agent.crud.addAgentAliasesRequest(
        new URI((json \ "content" \ "sessionURI").extract[String]),
        (json \ "content" \ "aliases").extract[List[String]]
      )
    )
  }
  def removeAgentAliasesRequest(json: JValue, key: String): Unit = {}
  def getAgentAliasesRequest(json: JValue, key: String): Unit = {}
  def getDefaultAliasRequest(json: JValue, key: String): Unit = {}
  def setDefaultAliasRequest(json: JValue, key: String): Unit = {}
  // Aliases
  def addAliasExternalIdentitiesRequest(json: JValue, key: String): Unit = {}
  def removeAliasExternalIdentitiesRequest(json: JValue, key: String): Unit = {}
  def getAliasExternalIdentitiesRequest(json: JValue, key: String): Unit = {}
  def setAliasDefaultExternalIdentityRequest(json: JValue, key: String): Unit = {}
  // Connections
  def addAliasConnectionsRequest(json: JValue, key: String): Unit = {}
  def removeAliasConnectionsRequest(json: JValue, key: String): Unit = {}
  def getAliasConnectionsRequest(json: JValue, key: String): Unit = {}
  def setAliasDefaultConnectionRequest(json: JValue, key: String): Unit = {}
  // Labels
  def addAliasLabelsRequest(json: JValue, key: String): Unit = {}
  def removeAliasLabelsRequest(json: JValue, key: String): Unit = {}
  def getAliasLabelsRequest(json: JValue, key: String): Unit = {}
  def setAliasDefaultLabelRequest(json: JValue, key: String): Unit = {}
  def getAliasDefaultLabelRequest(json: JValue, key: String): Unit = {}
  // DSL
  // def evalSubscribeRequest(JValue json, String key): Unit = {}
  def evalSubscribeCancelRequest(son: JValue, key: String): Unit = {}

  val jsonBlobLabel = fromTermString("jsonBlob(W)").getOrElse(throw new Exception("Couldn't parse jsonBlobLabel"))
  val pwmacLabel = fromTermString("pwmac(X)").getOrElse(throw new Exception("Couldn't parse pwmacLabel"))
  val emailLabel = fromTermString("email(Y)").getOrElse(throw new Exception("Couldn't parse emailLabel"))
  val tokenLabel = fromTermString("token(Z)").getOrElse(throw new Exception("Couldn't parse tokenLabel"))
  val aliasListLabel = fromTermString("aliasList(true)").getOrElse(throw new Exception("Couldn't parse aliasListLabel"))

  def confirmEmailToken(json: JValue, key: String): Unit = {
    val token = (json \ "content" \ "token").extract[String]
    val tokenUri = new URI("token://" + token)
    val tokenCnxn = PortableAgentCnxn(tokenUri, "token", tokenUri)
    
    val (erql, erspl) = agentMgr().makePolarizedPair()
    // TODO(mike): change from fetch to a consuming verb
    agentMgr().fetch(erql, erspl)(tokenLabel, List(tokenCnxn), (rsrc: Option[mTT.Resource]) => {
      rsrc match {
        case None => ()
        case Some(mTT.RBoundHM(Some(mTT.Ground( v )), _)) => {
          v match {
            case Bottom => {
              CompletionMapper.complete(key, compact(render(
                ("msgType" -> "createUserError")~
                ("content" ->
                  ("reason", "No such token.")
                )
              )))
            }
            case PostedExpr( postedStr : String ) => {
              val content = parse(postedStr)
              val email = (content \ "email").extract[String]
              val password = (content \ "password").extract[String]
              val jsonBlob = compact(render(content \ "jsonBlob"))
              secureSignup(email, password, jsonBlob, key)
            }
          }
        }
        case _ => throw new Exception("Unrecognized resource: " + rsrc)
      }
    })
  }
  
  // Compute the mac of an email address
  def emailToCap(email: String): String = {
    val macInstance = Mac.getInstance("HmacSHA256")
    macInstance.init(new SecretKeySpec("emailmac".getBytes("utf-8"), "HmacSHA256"))
    macInstance.doFinal(email.getBytes("utf-8")).map("%02x" format _).mkString.substring(0,36)
  }

  // Given an email, mac it then create Cnxn(mac, "emailhash", mac) and post "email(X): mac"
  // to show we know about the email.  Return the mac
  def storeCapByEmail(email: String): String = {
    val cap = emailToCap(email)
    val emailURI = new URI("emailhash://" + cap)
    val emailSelfCnxn = //new ConcreteHL.PortableAgentCnxn(emailURI, emailURI.toString, emailURI)
      PortableAgentCnxn(emailURI, "emailhash", emailURI)
    //val (erql, erspl) = agentMgr().makePolarizedPair()
    agentMgr().post[String](
      emailLabel,
      List(emailSelfCnxn),
      cap
    )
    cap
  }
  
  def secureSignup(
    email: String,
    password: String,
    jsonBlob: String,
    key: String
  ) : Unit = {
    import DSLCommLink.mTT
    val cap = if (email == "") UUID.randomUUID.toString else storeCapByEmail(email)
    BasicLogService.tweet("secureSignup email="+email+", password="+password+", cap="+cap)
    val macInstance = Mac.getInstance("HmacSHA256")
    macInstance.init(new SecretKeySpec("5ePeN42X".getBytes("utf-8"), "HmacSHA256"))
    val mac = macInstance.doFinal(cap.getBytes("utf-8")).slice(0,5).map("%02x" format _).mkString
    val capAndMac = cap + mac
    val capURI = new URI("agent://" + cap)
    val capSelfCnxn = PortableAgentCnxn(capURI, "identity", capURI)

    macInstance.init(new SecretKeySpec("pAss#4$#".getBytes("utf-8"), "HmacSHA256"))
    val pwmac = macInstance.doFinal(password.getBytes("utf-8")).map("%02x" format _).mkString

    BasicLogService.tweet("secureSignup posting pwmac")
    val (erql, erspl) = agentMgr().makePolarizedPair()
    agentMgr().post(erql, erspl)(
      pwmacLabel,
      List(capSelfCnxn),
      pwmac,
      ( optRsrc : Option[mTT.Resource] ) => {
        BasicLogService.tweet("secureSignup onPost1: optRsrc = " + optRsrc)
        optRsrc match {
          case None => ()
          case Some(_) => {
            val (erql, erspl) = agentMgr().makePolarizedPair()
            agentMgr().post(erql, erspl)(
              jsonBlobLabel,
              List(capSelfCnxn),
              jsonBlob,
              ( optRsrc : Option[mTT.Resource] ) => {
                BasicLogService.tweet("secureSignup onPost2: optRsrc = " + optRsrc)
                optRsrc match {
                  case None => ()
                  case Some(_) => {
                    val (erql, erspl) = agentMgr().makePolarizedPair()
                    agentMgr().post(erql, erspl)(
                      aliasListLabel,
                      List(capSelfCnxn),
                      """["alias"]""",
                      ( optRsrc : Option[mTT.Resource] ) => {
                        BasicLogService.tweet("secureSignup onPost3: optRsrc = " + optRsrc)
                        optRsrc match {
                          case None => ()
                          case Some(_) => {
                            CompletionMapper.complete(key, compact(render(
                              ("msgType" -> "createUserResponse") ~
                              ("content" -> ("agentURI" -> ("agent://cap/" + capAndMac))) 
                            )))
                          }
                        }
                      }
                    )
                  }
                }
              }
            )
          }
        }
      }
    )
  }

  def createUserRequest(json : JValue, key : String): Unit = {
    import DSLCommLink.mTT
    val email = (json \ "content" \ "email").extract[String].toLowerCase

    if (email == "") {
      // No email, sign up immediately with a random cap
      secureSignup(
        "",
        (json \ "content" \ "password").extract[String],
        compact(render(json \ "content" \ "jsonBlob")), 
        key
      )
    } else {
      // Email provided; send a confirmation email
      val token = UUID.randomUUID.toString.substring(0,8)
      val tokenUri = new URI("token://" + token)
      val tokenCnxn = PortableAgentCnxn(tokenUri, "token", tokenUri)

      val cap = emailToCap(email)
      val capURI = new URI("agent://" + cap)
      val capSelfCnxn = PortableAgentCnxn(capURI, "identity", capURI)

      val (erql, erspl) = agentMgr().makePolarizedPair()
      // See if the email is already there
      agentMgr().read( erql, erspl )(
        jsonBlobLabel,
        List(capSelfCnxn),
        (optRsrc: Option[mTT.Resource]) => {
          BasicLogService.tweet("createUserRequest | email case | anonymous onFetch: optRsrc = " + optRsrc)
          optRsrc match {
            case None => ()
            case Some(mTT.RBoundHM(Some(mTT.Ground(Bottom)), _)) => {
              // No such email exists, create it
              val (erql, erspl) = agentMgr().makePolarizedPair()
              agentMgr().post[String](erql, erspl)(
                tokenLabel,
                List(tokenCnxn),
                // email, password, and jsonBlob
                compact(render(json \ "content")),
                (optRsrc: Option[mTT.Resource]) => {
                  BasicLogService.tweet("createUserRequest | onPost: optRsrc = " + optRsrc)
                  optRsrc match {
                    case None => ()
                    case Some(_) => {
                      ConfirmationEmail.confirm(email, token)
                      // Notify user to check her email
                      CompletionMapper.complete(key, compact(render(
                        ("msgType" -> "createUserWaiting") ~
                        ("content" -> List()) // List() is rendered as "{}" 
                      )))
                    }
                  }
                }
              )
            }
            case _ => {
              CompletionMapper.complete(key, compact(render(
                ("msgType" -> "createUserError") ~
                ("content" ->
                  ("reason" -> "Email is already registered.")
                )
              )))
            }
          }
        }
      )
    }
  }
  

  def secureLogin(
    identType: String,
    identInfo: String,
    password: String,
    key: String
  ) : Unit = {
    import DSLCommLink.mTT
    
    def login(cap: String): Unit = {
      val capURI = new URI("agent://" + cap)
      val capSelfCnxn = PortableAgentCnxn(capURI, "identity", capURI)
      val onPwmacFetch: Option[mTT.Resource] => Unit = (rsrc) => {
        BasicLogService.tweet("secureLogin | login | onPwmacFetch: rsrc = " + rsrc)
        rsrc match {
          // At this point the cap is good, but we have to verify the pw mac
          case None => ()
          case Some(mTT.RBoundHM(Some(mTT.Ground(PostedExpr(pwmac: String))), _)) => {
            BasicLogService.tweet ("secureLogin | login | onPwmacFetch: pwmac = " + pwmac)
            val macInstance = Mac.getInstance("HmacSHA256")
            macInstance.init(new SecretKeySpec("pAss#4$#".getBytes("utf-8"), "HmacSHA256"))
            val hex = macInstance.doFinal(password.getBytes("utf-8")).map("%02x" format _).mkString
            BasicLogService.tweet ("secureLogin | login | onPwmacFetch: hex = " + hex)
            if (hex != pwmac.toString) {
              BasicLogService.tweet("secureLogin | login | onPwmacFetch: Password mismatch.")
              CompletionMapper.complete(key, compact(render(
                ("msgType" -> "initializeSessionError") ~
                ("content" -> ("reason" -> "Bad password.")) 
              )))
            } else {
              def onAliasesFetch(jsonBlob: String): Option[mTT.Resource] => Unit = (optRsrc) => {
                BasicLogService.tweet("secureLogin | login | onPwmacFetch | onJSONBlobFetch: optRsrc = " + optRsrc)
                optRsrc match {
                  case None => ()
                  case Some(rbnd@mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                    v match {
                      case PostedExpr(aliasList: String) => {
                        val content = 
                          ("sessionURI" -> ("agent-session://" + cap)) ~
                          ("listOfAliases" -> parse(aliasList)) ~
                          ("defaultAlias" -> "") ~
                          ("listOfLabels" -> List[String]()) ~ // for default alias
                          ("listOfCnxns" -> List[String]()) ~  // for default alias
                          ("lastActiveLabel" -> "") ~
                          ("jsonBlob" -> parse(jsonBlob))

                        CompletionMapper.complete(key, compact(render(
                          ("msgType" -> "initializeSessionResponse") ~
                          ("content" -> content) 
                        )))
                      }
                      case Bottom => {
                        CompletionMapper.complete(key, compact(render(
                          ("msgType" -> "initializeSessionError") ~
                          ("content" -> ("reason" -> "Strange: found pwmac and jsonBlob but not aliases!?"))
                        )))
                      }
                    }
                  }
                }
              }
              val onJSONBlobFetch: Option[mTT.Resource] => Unit = (optRsrc) => {
                BasicLogService.tweet("secureLogin | login | onPwmacFetch | onJSONBlobFetch: optRsrc = " + optRsrc)
                optRsrc match {
                  case None => ()
                  case Some(rbnd@mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                    v match {
                      case PostedExpr(jsonBlob: String) => {
                        val (erql, erspl) = agentMgr().makePolarizedPair()
                        agentMgr().fetch( erql, erspl )(aliasListLabel, List(capSelfCnxn), onAliasesFetch(jsonBlob))
                      }
                      case Bottom => {
                        CompletionMapper.complete(key, compact(render(
                          ("msgType" -> "initializeSessionError") ~
                          ("content" -> ("reason" -> "Strange: found pwmac but not jsonBlob!?"))
                        )))
                      }
                    }
                  }
                  case _ => {
                    throw new Exception("Unrecognized resource: " + optRsrc)
                  }
                }
              }
              val (erql, erspl) = agentMgr().makePolarizedPair()
              agentMgr().fetch( erql, erspl )(jsonBlobLabel, List(capSelfCnxn), onJSONBlobFetch)
              ()
            }
          }
          case _ => {
            BasicLogService.tweet("Unrecognized resource: " + rsrc)
          }
        }
      }
      val (erql, erspl) = agentMgr().makePolarizedPair()
      BasicLogService.tweet ("secureLogin | login: fetching with eqrl, erspl = " + erql + ", " + erspl)
      agentMgr().fetch( erql, erspl )(pwmacLabel, List(capSelfCnxn), onPwmacFetch)
    }
    
    // identType is either "cap" or "email"
    identType match {
      case "cap" => {
        BasicLogService.tweet("secureLogin | cap branch")
        val cap = identInfo.slice(0, 36)
        val mac = identInfo.slice(36, 46)
        val macInstance = Mac.getInstance("HmacSHA256")
        macInstance.init(new SecretKeySpec("5ePeN42X".getBytes("utf-8"), "HmacSHA256"))
        val hex = macInstance.doFinal(cap.getBytes("utf-8")).slice(0,5).map("%02x" format _).mkString
        if (hex != mac) {
          CompletionMapper.complete(key, compact(render(
            ("msgType" -> "initializeSessionError") ~
            ("content" -> ("reason" -> "This link wasn't generated by us.")) 
          )))
        } else {
          BasicLogService.tweet("Link OK, logging in")
          login(cap)
        }
      }
      
      case "email" => {
        val email = identInfo.toLowerCase
        BasicLogService.tweet("secureLogin | email branch: email = " + email)
        // hash the email to get cap
        val cap = emailToCap(email)
        // don't need mac of cap; need to verify email is on our network
        val emailURI = new URI("emailhash://" + cap)
        val emailSelfCnxn = PortableAgentCnxn(emailURI, "emailhash", emailURI)
        val (erql, erspl) = agentMgr().makePolarizedPair()
        BasicLogService.tweet("secureSignup | email branch: erql, erspl = " + erql + ", " + erspl)
        agentMgr().fetch(erql, erspl)(
          emailLabel,
          List(emailSelfCnxn),
          (optRsrc: Option[mTT.Resource]) => {
            BasicLogService.tweet("secureLogin | email case | anonymous onFetch: optRsrc = " + optRsrc)
            optRsrc match {
              case None => ()
              case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                v match {
                  case Bottom => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError")~
                      ("content" -> 
                        ("reason" -> "No such email.")
                      )
                    )))
                  }
                  case PostedExpr(cap: String) => {
                    login(cap)
                  }
                }
              }
              case _ => {
                throw new Exception("Unrecognized resource: optRsrc = " + optRsrc)
              }
            }
          }
        )
      }
    }
  }

  def initializeSessionRequest(
    json : JValue,
    key : String
  ): Unit = {
    val agentURI = (json \ "content" \ "agentURI").extract[String]
    val uri = new URI(agentURI)

    if (uri.getScheme() != "agent") {
      throw InitializeSessionException(agentURI, "Unrecognized scheme")
    }
    val identType = uri.getHost()
    val identInfo = uri.getPath.substring(1) // drop leading slash
    // TODO: get a proper library to do this
    val queryMap = new HashMap[String, String]
    uri.getRawQuery.split("&").map((x: String) => {
      val pair = x.split("=")
      queryMap += ((pair(0), pair(1)))
    })
    var password = queryMap.get("password").getOrElse("")
    secureLogin(identType, identInfo, password, key)
  }

  def extractCnxn(cx: JObject) = new PortableAgentCnxn(
    new URI((cx \ "src").extract[String]),
    (cx \ "label").extract[String],
    new URI((cx \ "tgt").extract[String])
  )

  def extractLabelAndCnxns(exprContent: JObject) = {
    BasicLogService.tweet("Extracting from " + compact(render(exprContent)))
    val label = fromTermString((exprContent \ "label").extract[String]).getOrElse(
      throw new Exception("Couldn't parse label: " + compact(render(exprContent)))
    )
    val cnxns = (exprContent \ "cnxns") match {
      case JArray(arr: List[JObject]) => arr.map(extractCnxn _)
    }
    (label, cnxns)
  }

  def evalSubscribeRequest(json: JValue, key: String) : Unit = {
    import com.biosimilarity.evaluator.distribution.portable.v0_1._

    BasicLogService.tweet("evalSubscribeRequest: json = " + compact(render(json)));
    val content = (json \ "content").asInstanceOf[JObject]
    val sessionURIstr = (content \ "sessionURI").extract[String]
    val (erql, erspl) = agentMgr().makePolarizedPair()
    BasicLogService.tweet("evalSubscribeRequest: erql = " + erql + ", erspl = " + erspl)
    
    val expression = (content \ "expression")
    val ec = (expression \ "content").asInstanceOf[JObject]
    val (label, cnxns) = extractLabelAndCnxns(ec)
    val exprType = (expression \ "msgType").extract[String]
    exprType match {
      case "feedExpr" => {
        BasicLogService.tweet("evalSubscribeRequest | feedExpr")
        val onFeed: Option[mTT.Resource] => Unit = (rsrc) => {
          BasicLogService.tweet("evalSubscribeRequest | onFeed: rsrc = " + rsrc)
          rsrc match {
            case None => ()
            case Some(mTT.RBoundHM(Some(mTT.Ground(PostedExpr(postedStr: String))), _)) => {
              val content =
                ("sessionURI" -> sessionURIstr) ~
                ("pageOfPosts" -> List(postedStr))
              val response = ("msgType" -> "evalSubscribeResponse") ~ ("content" -> content)
              BasicLogService.tweet("evalSubscribeRequest | onFeed: response = " + compact(render(response)))
              CometActorMapper.cometMessage(key, sessionURIstr, compact(render(response)))
            }
            case _ => throw new Exception("Unrecognized resource: " + rsrc)
          }
        }
        BasicLogService.tweet("evalSubscribeRequest | feedExpr: calling feed")
        agentMgr().feed(erql, erspl)(label, cnxns, onFeed)
      }
      case "scoreExpr" => {
        BasicLogService.tweet("evalSubscribeRequest | scoreExpr")
        val onScore: Option[mTT.Resource] => Unit = (rsrc) => {
          BasicLogService.tweet("evalSubscribeRequest | onScore: rsrc = " + rsrc)
          rsrc match {
            case None => ()
            case Some(mTT.RBoundHM(Some(mTT.Ground(PostedExpr(postedStr: String))), _)) => {
              val content =
                ("sessionURI" -> sessionURIstr) ~
                ("pageOfPosts" -> List(postedStr))
              val response = ("msgType" -> "evalSubscribeResponse") ~ ("content" -> content)
              BasicLogService.tweet("evalSubscribeRequest | onScore: response = " + compact(render(response)))
              CometActorMapper.cometMessage(key, sessionURIstr, compact(render(response)))
            }
            case _ => throw new Exception("Unrecognized resource: " + rsrc)
          }
        }
        val staff = (expression \ "content" \ "staff") match {
          case JObject(List((which: String, vals@JArray(_)))) => {
            // Either[Seq[PortableAgentCnxn],Seq[CnxnCtxtLabel[String,String,String]]]
            which match {
              case "a" => vals match {
                case JArray(arr: List[JObject]) => Left(arr.map(extractCnxn _))
              }
              case "b" => Right(
                vals.extract[List[String]].map((t: String) => 
                  fromTermString(t).getOrElse(throw new Exception("Couldn't parse staff: " + json))
                )
              )
              case _ => throw new Exception("Couldn't parse staff: " + json)
            }
          }
          case _ => throw new Exception("Couldn't parse staff: " + json)
        }
        BasicLogService.tweet("evalSubscribeRequest | feedExpr: calling score")
        agentMgr().score(erql, erspl)(label, cnxns, staff, onScore)
      }
      case "insertContent" => {
        BasicLogService.tweet("evalSubscribeRequest | insertContent")
        val value = (ec \ "value").extract[String]
        BasicLogService.tweet("evalSubscribeRequest | insertContent: calling post")
        agentMgr().post(erql, erspl)(
          label,
          cnxns,
          value,
          (rsrc: Option[mTT.Resource]) => {
            println("evalSubscribeRequest | insertContent | onPost")
            BasicLogService.tweet("evalSubscribeRequest | onPost: rsrc = " + rsrc)
            rsrc match {
              case None => ()
              case Some(_) => {
                // evalComplete, empty seq of posts
                val content =
                  ("sessionURI" -> sessionURIstr) ~
                  ("pageOfPosts" -> List[String]())
                val response = ("msgType" -> "evalComplete") ~ ("content" -> content)
                BasicLogService.tweet("evalSubscribeRequest | onPost: response = " + compact(render(response)))
                CometActorMapper.cometMessage(key, sessionURIstr, compact(render(response)))
              }
            }
          }
        )
      }
      case _ => {
        throw new Exception("Unrecognized request: " + compact(render(json)))
      }
    }
  }  

  def connectServers( key : String, sessionId : UUID ) : Unit = {
    connectServers( sessionId )(
      ( optRsrc : Option[mTT.Resource] ) => {
        println( "got response: " + optRsrc )
        optRsrc match {
          case None => ()
          case Some(rsrc) => CompletionMapper.complete( key, rsrc.toString )
        }
      }
    )    
  }

  def connectServers( sessionId : UUID )(
    onConnection : Option[mTT.Resource] => Unit =
      ( optRsrc : Option[mTT.Resource] ) => { println( "got response: " + optRsrc ) }
  ) : Unit = {
    val pulseErql = agentMgr().adminErql( sessionId )
    val pulseErspl = agentMgr().adminErspl( sessionId )
    ensureServersConnected(
      pulseErql,
      pulseErspl
    )(
      onConnection
    )
  }

  def sessionPing(json: JValue) : String = {
    val sessionURI = (json \ "content" \ "sessionURI").extract[String]
    // TODO: check sessionURI validity
    
    sessionURI
  }

  def closeSessionRequest(json: JValue, key: String) : Unit = {
    val sessionURI = (json \ "content" \ "sessionURI").extract[String]

    CompletionMapper.complete(key, compact(render(
      ("msgType" -> "closeSessionResponse")~
      ("content" ->
        ("sessionURI" -> sessionURI)
      )
    )))
  }
}


