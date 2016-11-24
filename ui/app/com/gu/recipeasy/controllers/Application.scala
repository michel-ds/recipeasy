package controllers

import java.time.OffsetDateTime

import play.api.mvc._
import play.api.Configuration
import play.api.libs.ws.WSClient
import play.api.data._
import play.api.data.format.Formats._
import play.api.i18n.{ I18nSupport, MessagesApi }
import com.typesafe.scalalogging.StrictLogging
import com.gu.recipeasy.auth.AuthActions
import com.gu.recipeasy.db._
import com.gu.recipeasy.models._
import com.gu.recipeasy.views
import models._
import models.CuratedRecipeForm._

class Application(override val wsClient: WSClient, override val conf: Configuration, db: DB, val messagesApi: MessagesApi) extends Controller with AuthActions with I18nSupport with StrictLogging {

  def index = AuthAction { implicit request =>
    val curationIndex: Double = (db.curationCompletionRatio() * 100) // expected to be between 0 and 100
    val verificationIndex: Double = (db.verificationCompletionRatio() * 100) // expected to be between 0 and 100
    Ok(views.html.app("Recipeasy", curationIndex, verificationIndex))
  }

  def tutorial() = AuthAction { implicit request =>
    Ok(views.html.tutorial())
  }

  // -------------------------------------------------------

  def viewRecipe(id: String) = AuthAction { implicit request =>
    val recipe = db.getOriginalRecipe(id)
    curatedRecipedEditor(recipe, editable = false)
  }

  def curateOrVerify() = AuthAction { implicit request =>
    if (isShowCreation()) {
      val newRecipe = db.getOriginalRecipeInNewStatus
      newRecipe match {
        case Some(recipe) => Redirect(routes.Application.curateRecipe(recipe.id))
        case None => NotFound
      }
    } else {
      val maybeRecipe = db.getCuratedRecipe()
      maybeRecipe match {
        case Some(recipe) => Redirect(routes.Application.verifyRecipe(recipe.recipeId))
        case None => NotFound
      }
    }
  }

  def curateRecipe(id: String) = AuthAction { implicit request =>
    db.setOriginalRecipeStatus(id, Pending)
    val recipe = db.getOriginalRecipe(id)
    curatedRecipedEditor(recipe, editable = true)
  }

  def verifyRecipe(id: String) = AuthAction { implicit request =>
    val recipe = db.getOriginalRecipe(id)
    // We reuse the code for `curateRecipe` because curation and verification use the same logic and the same editor
    // But we need to record the fact that the recipe is being verified.
    curatedRecipedEditor(recipe, editable = true)
  }

  def finalCheckRecipe(id: String) = AuthAction { implicit request =>
    val recipe = db.getOriginalRecipe(id)
    // We reuse the code for `curateRecipe` because curation and verification use the same logic and the same editor
    // But we need to record the fact that the recipe is being verified.
    curatedRecipedEditor(recipe, editable = true)
  }

  def curateOneRecipeInNewStatus = AuthAction { implicit request =>
    val newRecipe = db.getOriginalRecipeInNewStatus
    newRecipe match {
      case Some(r) => Redirect(routes.Application.curateRecipe(r.id))
      case None => NotFound
    }
  }

  def postCuratedRecipe(recipeId: String) = AuthAction { implicit request =>
    val formValidationResult = Application.curatedRecipeForm.bindFromRequest
    formValidationResult.fold({ formWithErrors =>
      val originalRecipe = db.getOriginalRecipe(recipeId)
      originalRecipe match {
        case Some(recipe) => {
          logger.debug(s"Incorrect form submission $recipeId")
          BadRequest(views.html.error("Recipeasy", "Incorrect form submission"))
        }
        case None => NotFound
      }
    }, { r =>
      val curatedRecipeWithoutId = fromForm(r)
      val curatedRecipeWithId = curatedRecipeWithoutId.copy(recipeId = recipeId, id = 0L)
      db.deleteCuratedRecipeByRecipeId(recipeId)
      db.insertCuratedRecipe(curatedRecipeWithId)
      db.moveStatusForward(recipeId)
      db.getOriginalRecipeStatus(recipeId).foreach(status => db.insertUserEvent(UserEvent(request.user.email, request.user.firstName, request.user.lastName, recipeId, recipeStatusToUserEventOperationType(status))))
      Redirect(routes.Application.curateOneRecipeInNewStatus)
    })
  }

  def adminLandingPage = AuthAction { implicit request =>
    Ok(views.html.adminlanding())
  }

  def recentActivity = AuthAction { implicit request =>
    val userEventDBs: List[UserEventDB] = db.userEvents()
    Ok(views.html.recentactivity(userEventDBs))
  }

  def statusDistribution = AuthAction { implicit request =>
    val distribution: Map[RecipeStatus, Long] = Map(
      New -> db.countRecipesInGivenStatus(New),
      Ready -> db.countRecipesInGivenStatus(Ready),
      Pending -> db.countRecipesInGivenStatus(Pending),
      Curated -> db.countRecipesInGivenStatus(Curated),
      Verified -> db.countRecipesInGivenStatus(Verified),
      Finalised -> db.countRecipesInGivenStatus(Finalised),
      Impossible -> db.countRecipesInGivenStatus(Impossible)
    )
    Ok(views.html.statusdistribution(distribution))
  }

  // -------------------------------------------------------

  private def isShowCreation(): Boolean = {
    val ParsingTime = db.countRecipesInGivenStatus(New) * 4
    val VerificationTime = db.countRecipesInGivenStatus(Curated) * 2 + db.countRecipesInGivenStatus(Verified)
    ParsingTime >= VerificationTime
  }

  private def recipeStatusToUserEventOperationType(status: RecipeStatus): String = {
    // We are currently emitting a string
    // TODO: emit a proper OperationType
    status match {
      case Curated => "Curation"
      case Verified => "Verification"
      case Finalised => "Confirmation"
      case _ => "Curation"
    }
  }

  private[this] def curatedRecipedEditor(
    recipe: Option[Recipe],
    editable: Boolean
  )(implicit req: RequestHeader) = {
    recipe match {
      case Some(r) => {

        /* if recipe has not being edited yet, mark as currently edited */
        if (r.status == New && editable) {
          db.setOriginalRecipeStatus(r.id, Pending)
        }

        val curatedRecipe = db.getCuratedRecipeByRecipeId(r.id).map(CuratedRecipe.fromCuratedRecipeDB) getOrElse CuratedRecipe.fromRecipe(r)
        val curatedRecipeForm = CuratedRecipeForm.toForm(curatedRecipe)
        val images = db.getImages(r.articleId)

        logger.info(s"View ${r.id}, ${r.title}")
        Ok(views.html.recipe(
          Application.curatedRecipeForm.fill(curatedRecipeForm),
          r.id,
          r.body,
          r.articleId,
          editable,
          images,
          r.status
        ))

      }
      case None => NotFound
    }
  }

}

object Application {
  import models.TagHelper._
  import Forms._
  val curatedRecipeForm: Form[CuratedRecipeForm] = Form(
    mapping(
      "title" -> nonEmptyText(maxLength = 200),
      "serves" -> optional(mapping(
        "portionType" -> text.transform[PortionType](PortionType.fromString(_), _.toString),
        "quantity" -> mapping(
          "from" -> number(min = 1),
          "to" -> number(min = 1)
        )(Serves.apply)(Serves.unapply)
      )(DetailedServes.apply)(DetailedServes.unapply)),
      "ingredientsLists" -> seq(mapping(
        "title" -> optional(nonEmptyText(maxLength = 200)),
        "ingredients" -> seq(mapping(
          "quantity" -> optional(of[Double]),
          "unit" -> optional(text.transform[CookingUnit](CookingUnit.fromString(_).getOrElse(Handful), _.abbreviation)),
          "item" -> nonEmptyText(maxLength = 200),
          "comment" -> optional(text(maxLength = 200)),
          "raw" -> text
        )(DetailedIngredient.apply)(DetailedIngredient.unapply))
      )(DetailedIngredientsList.apply)(DetailedIngredientsList.unapply)),
      "credit" -> optional(text(maxLength = 200)),
      "times" -> mapping(
        "preparationHours" -> optional(of[Int]),
        "preparationMinutes" -> optional(of[Int]),
        "cookingHours" -> optional(of[Int]),
        "cookingMinutes" -> optional(of[Int])
      )(TimesInMinsAdapted.apply)(TimesInMinsAdapted.unapply),
      "steps" -> seq(text),
      "tags" -> mapping(
        "cuisine" -> seq(text),
        "category" -> seq(text),
        "holiday" -> seq(text),
        "dietary" -> seq(text)
      )(FormTags.apply)(FormTags.unapply),
      "images" -> seq(mapping(
        "mediaId" -> (text),
        "assetUrl" -> (text),
        "altText" -> (text)
      )(Image.apply)(Image.unapply))
    )(CuratedRecipeForm.apply)(CuratedRecipeForm.unapply)
  )
}

