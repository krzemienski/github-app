package io.github.manuzhang.graphql

import io.github.manuzhang.Utils.getFirstNode

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

object GetAwesomeVideoRepos extends GraphQlApp {

  override def run():Unit = {
    val readme = getReadme("krzemienski", "awesome-video")

    val fs = readme.split("\n").map(_.split(" - ")).collect {
      case parts if parts.length == 2 =>
        val link = parts(0)
        val desc = parts(1)

        val queryRepo =
          """
           query($owner: String!, $name: String!) {
             repository(owner: $owner, name: $name) {
               forks {
                 totalCount
               }
               stargazers {
                 totalCount
               }
               refs(refPrefix: "refs/tags/", first: 1, orderBy: {field: TAG_COMMIT_DATE, direction: DESC}) {
                 nodes {
                   name
                 }
               }
               defaultBranchRef {
                 target {
                   ... on Commit {
                     history(first: 1) {
                       nodes {
                         pushedDate
                       }
                     }
                   }
                 }
               }
               pushedAt
               isArchived
             }
           }
          """.stripMargin



        val regex = "\\[([^\\[\\]]+)\\]\\(https://github.com/([^/]+)/([^/)]+)/?\\)".r
        regex.findFirstMatchIn(link).collect { case regex(displayName, owner, name) =>

          runV4PostAsync(queryRepo, getVariables(owner, name)).collect {
            case response if response != ujson.Null =>
              val repo = response.obj("repository")
              Try {
                ujson.Obj(
                  "name" -> displayName,
                  "link" -> s"https://github.com/$owner/$name",
                  "description" -> desc,
                  "stars" -> repo.obj("stargazers").obj("totalCount").num,
                  "forks" -> repo.obj("forks").obj("totalCount").num,
                  "lastTag" -> getFirstNode(repo.obj("refs"))
                    .map(_.obj("name")).getOrElse(ujson.Str("")),
                  "lastUpdate" -> getFirstNode(repo.obj("defaultBranchRef").obj("target").obj("history"))
                    .map(_.obj("pushedDate")).getOrElse(repo.obj("pushedAt")),
                  "isArchived" -> repo.obj("isArchived")
                )
              } match {
                case Success(v) => v
                case Failure(e) =>
                  println(s"$owner,$name,$response")
                  throw e
              }
          }
        }
      // TODO: what is this ?
    }.filter(_.nonEmpty).map(_.get).toList

    val json = Await.result(Future.sequence(fs), Duration.Inf).render(indent = 2)
    os.write.over(os.pwd / "awesome-video-repos.json", json)
  }

  def getReadme(owner: String, name: String): String = {
    val query =
      """
       query($owner: String!, $name: String!) {
         repository(owner: $owner, name: $name) {
           object(expression: "master:README.md") {
             ... on Blob {
               text
             }
           }
         }
       }
      """.stripMargin

    val response = runV4Post(query, getVariables(owner, name))
    response.obj("repository").obj("object").obj("text").str
  }

  def getVariables(owner: String, name: String): String = {
    ujson.Obj("owner" -> owner, "name" -> name).toString()
  }
}
