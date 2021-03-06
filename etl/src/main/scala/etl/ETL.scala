package etl

import com.gu.contentapi.client._
import com.gu.contentapi.client.model._
import com.gu.contentapi.client.model.v1._
import com.gu.recipeasy.models._
import com.gu.recipeasy.db._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import cats.kernel.Monoid
import cats.Foldable
import cats.std.list._
import cats.syntax.foldable._

import scala.language.higherKinds
import java.time.OffsetDateTime

import org.apache.commons.codec.digest.DigestUtils._
import com.gu.recipeasy.db.ContextWrapper
import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.AsyncHttpClientConfig.Builder
import com.typesafe.config.ConfigFactory
import dispatch.Http

case class CapiAuth(username: String, password: String)

class ETLGuardianContentClient(override val apiKey: String) extends GuardianContentClient(apiKey) {

  override protected lazy val http: Http = {

    val config = new Builder()
      .setAllowPoolingConnections(true)
      .setMaxConnectionsPerHost(2000)
      .setMaxConnections(1000)
      .setConnectTimeout(1000)
      .setRequestTimeout(2000)
      .setCompressionEnforced(true)
      .setFollowRedirect(true)
      .setUserAgent(userAgent)
      .setConnectionTTL(60000)

    Http(new AsyncHttpClient(config.build()))
  }

}

case class Progress(pagesProcessed: Int, articlesProcessed: Int, recipesFound: Int, articlesWithNoRecipes: List[String]) {
  override def toString: String = s"$pagesProcessed pages processed,\t$articlesProcessed articles processed,\t$recipesFound recipes found,\t${articlesWithNoRecipes.size} articles with no recipes"
}

object Progress {
  val empty = Progress(0, 0, 0, Nil)
  implicit val progressMonoid: Monoid[Progress] = new Monoid[Progress] {
    def empty = Progress.empty
    def combine(x: Progress, y: Progress) = Progress(
      x.pagesProcessed + y.pagesProcessed,
      x.articlesProcessed + y.articlesProcessed,
      x.recipesFound + y.recipesFound,
      x.articlesWithNoRecipes ++ y.articlesWithNoRecipes
    )
  }
}

object Images {

  private def groupArticles(articlesWithoutImages: List[String]): List[String] = {
    val groups = articlesWithoutImages.grouped(10).toList
    groups.map(group => group.mkString(","))
  }

  private def getArticleResponse(articleIds: String)(implicit capiClient: GuardianContentClient): Future[SearchResponse] = {
    capiClient.getResponse(capiClient.search.ids(articleIds).showElements("image"))
  }

  def waitForResponse(group: String)(implicit capiClient: GuardianContentClient): SearchResponse = {
    val response: Future[SearchResponse] = getArticleResponse(group)
    response.onFailure { case e => waitForResponse(group) }
    Await.result(response, 10.second)
  }

  def getArticlesMissingImages()(implicit db: DB, capiClient: GuardianContentClient): List[SearchResponse] = {
    val articlesMissingImages = (db.getArticlesWithRecipes().toSet diff db.getArticlesWithSavedImages().toSet).toList
    val articleIdsParamGroups: List[String] = groupArticles(articlesMissingImages)

    articleIdsParamGroups.map(group => {
      waitForResponse(group)
    })
  }

  def insertImagesFromArticles(articles: List[SearchResponse])(implicit db: DB): Unit = {
    articles.foreach { article =>
      val images = for (results <- article.results) yield ImageExtraction.getImages(Some(results))
      images.foreach(image => {
        db.insertImages(image.toList)
      })
    }
  }
}

object ETL extends App {

  if (args.isEmpty) {
    Console.err.println("Usage: ETL <CAPI key>")
    sys.exit(1)
  }

  val capiKey = args(0)
  val query = SearchQuery()
    .pageSize(100)
    .contentType("article") // there are some video recipes, don't want those
    .tag("tone/recipes,-lifeandstyle/series/the-lunch-box,-lifeandstyle/series/last-bites,-lifeandstyle/series/breakfast-of-champions")
    .showFields("main,body,byline")
    .showElements("image")

  val contextWrapper = new ContextWrapper { val config = ConfigFactory.load() }

  try {
    implicit val capiClient = new ETLGuardianContentClient(capiKey)
    implicit val db = new DB(contextWrapper)
    try {
      val firstPage = Await.result(capiClient.getResponse(query), 5.seconds)
      val pages = (1 to firstPage.pages).toList

      val endResult = processAllRecipeArticles(pages)
      println(s"Finished! End result: $endResult")
      println("Articles with no recipes:")
      endResult.articlesWithNoRecipes.foreach(id => println(s"- https://www.theguardian.com/$id"))

      val articles: List[SearchResponse] = Images.getArticlesMissingImages()
      Images.insertImagesFromArticles(articles)
    } finally {
      capiClient.shutdown()
    }
  } finally {
    contextWrapper.dbContext.close()
  }

  def processAllRecipeArticles(pages: List[Int])(implicit capiClient: GuardianContentClient, db: DB): Progress = {
    foldMapWithLogging(pages) { p =>
      val progress = Try(Await.result(capiClient.getResponse(query.page(p)), 5.seconds)) match {
        case Success(response) =>
          println(s"Processing page $p of CAPI results")
          processPage(response.results.toList)
        case Failure(e) =>
          println(s"Skipping page $p because of CAPI failure (${e.getMessage})")
          Progress.empty
      }

      Thread.sleep(500) // avoid spamming CAPI
      progress
    }
  }

  def processPage(contents: List[Content])(implicit db: DB): Progress = {
    Progress.progressMonoid.combine(processPageWhenInserting(contents), processPageWhenUpdating(contents))
  }

  def processPageWhenInserting(contents: List[Content])(implicit db: DB): Progress = {
    val freshContent = contents.filterNot(c => db.existingArticlesIds contains (c.id))

    val progress = freshContent.foldMap { content =>
      println(s"Processing fresh content ${content.id}")
      val recipes = getRecipes(content)(db.insertImages(_))
      //println(s"Found ${recipes.size} recipes:")
      recipes.foreach(r => println(s" - ${r.title}, \n  -${r.serves}, \n   -${r.ingredientsLists}, \n    -${r.steps}"))
      println()
      db.insertAll(recipes.toList)
      makeProgress(recipes, content)
    }
    progress.copy(pagesProcessed = 1)
  }

  def processPageWhenUpdating(contents: List[Content])(implicit db: DB): Progress = {
    //if a user has edited a recipe we do not want to reparse its parent article
    val articlesSafeToProcess = db.existingArticlesIds diff (db.editedArticlesIds)
    val articlesToUpdate = contents.filter(c => articlesSafeToProcess contains (c.id))

    val progress = articlesToUpdate.foldMap { content =>
      println(s"Processing content ${content.id}")
      val noOp: List[ImageDB] => Unit = (_) => Unit
      val recipes = getRecipes(content)(noOp)
      //println(s"Found ${recipes.size} recipes:")
      recipes.foreach(r => println(s" - ${r.title}, \n  -${r.serves}, \n   -${r.ingredientsLists}, \n    -${r.steps}"))
      println()
      db.updateAll(recipes.toList)
      makeProgress(recipes, content)

    }
    progress.copy(pagesProcessed = 1)
  }

  def getRecipes(content: Content)(insertImages: List[ImageDB] => Unit): Seq[Recipe] = {

    val rawRecipes: Seq[RawRecipe] = RecipeExtraction.findRecipes(content.webTitle, content.fields.flatMap(_.body).getOrElse(""))
    if (rawRecipes.nonEmpty) { insertImages(ImageExtraction.getImages(Some(content)).toList) }
    val parsedRecipes = rawRecipes.map(RecipeParsing.parseRecipe)
    val publicationDate = content.webPublicationDate.map(time => OffsetDateTime.parse(time.iso8601)).getOrElse(OffsetDateTime.now)

    parsedRecipes.zipWithIndex.map {
      case (r, i) =>
        // include index because some recipes inside the same article have duplicate titles! (due to a parser bug)
        val id = md5Hex(s"${r.title} :: ${content.id} :: $i")
        Recipe(
          id = id,
          title = r.title,
          body = r.body,
          serves = r.serves,
          ingredientsLists = IngredientsLists(r.ingredientsLists),
          articleId = content.id,
          credit = content.fields.flatMap(_.byline),
          publicationDate,
          status = RecipeStatusNew,
          steps = Steps(r.steps)
        )
    }
  }

  def makeProgress(recipes: Seq[Recipe], content: Content): Progress = {
    Progress(
      pagesProcessed = 0,
      articlesProcessed = 1,
      recipesFound = recipes.size,
      articlesWithNoRecipes = if (recipes.isEmpty) List(content.id) else Nil
    )
  }

  def foldMapWithLogging[A, B, F[_]](fa: F[A])(f: A => B)(implicit Fo: Foldable[F], B: Monoid[B]): B = {
    Fo.foldLeft(fa, B.empty) { (b, a) =>
      val progress = B.combine(b, f(a))
      println(s"Progress: $progress")
      progress
    }
  }

}

