package com.seamless.diff

import com.seamless.contexts.requests.Commands._
import com.seamless.contexts.requests._
import com.seamless.contexts.rfc.RfcState
import com.seamless.ddd.ExportedCommand
import com.seamless.diff.ShapeDiffer.ShapeDiffResult
import io.circe._
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.export.Exported

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportAll, JSExportDescendentClasses}
import scala.util.{Failure, Success, Try}

@JSExport
case class ApiRequest(url: String, method: String, contentType: String, body: Option[Json] = None)

@JSExport
case class ApiResponse(statusCode: Int, contentType: String, body: Option[Json] = None)

@JSExport
case class ApiInteraction(apiRequest: ApiRequest, apiResponse: ApiResponse)

@JSExport
@JSExportAll
object JsonHelper {

  import js.JSConverters._

  def fromString(s: String): Json = {
    import io.circe.parser._
    Try {
      parse(s).right.get
    } match {
      case Failure(exception) => {
        println(exception)
        Json.Null
      }
      case Success(value) => value
    }
  }

  def toSome(x: Json): Option[Json] = Some(x)

  def toNone(): Option[Json] = None

  //def fromAny(any: js.Any): Json = any.asJson

  def seqToJsArray(x: Seq[AnyVal]): js.Array[AnyVal] = {
    x.toJSArray
  }

  def iteratorToJsIterator(x: Iterator[AnyVal]): js.Iterator[AnyVal] = {
    x.toJSIterator
  }
}

@JSExport
@JSExportAll
object RequestDiffer {

  @JSExportAll
  sealed trait RequestDiffResult {
    def asJs = {
      import io.circe.scalajs.convertJsonToJs
      convertJsonToJs(this.asJson)
    }
  }
  case class UnmatchedUrl(interaction: ApiInteraction) extends RequestDiffResult
  case class UnmatchedHttpMethod(pathId: PathComponentId, method: String, interaction: ApiInteraction) extends RequestDiffResult
  case class UnmatchedHttpStatusCode(requestId: RequestId, statusCode: Int, interaction: ApiInteraction) extends RequestDiffResult
  case class UnmatchedResponseContentType(responseId: ResponseId, contentType: String, previousContentType: String, statusCode: Int) extends RequestDiffResult
  case class UnmatchedResponseBodyShape(responseId: ResponseId, contentType: String, responseStatusCode: Int, shapeDiff: ShapeDiffResult) extends RequestDiffResult
  case class UnmatchedRequestBodyShape(requestId: RequestId, contentType: String, shapeDiff: ShapeDiffResult) extends RequestDiffResult
  case class UnmatchedRequestContentType(requestId: RequestId, contentType: String, previousContentType: String) extends RequestDiffResult

  case class PipelineItem[T](item: Option[T], results: Iterator[RequestDiffResult])

  def compare(interaction: ApiInteraction, spec: RfcState): Iterator[RequestDiffResult] = {
    compare(ApiInteractionLike.fromApiInteraction(interaction), spec)
  }

  def compare(interaction: ApiInteractionLike, spec: RfcState): Iterator[RequestDiffResult] = {
    val pathPipeline = pathDiff(interaction, spec)
    pathPipeline.item match {
      case None => pathPipeline.results
      case Some(pathId) => {
        val requestPipeline = requestDiff(interaction, spec, pathId)
        requestPipeline.item match {
          case None => pathPipeline.results ++ requestPipeline.results
          case Some(request) => {
            val responsePipeline = responseDiff(interaction, spec, request.requestId)
            requestPipeline.results ++ responsePipeline.results
          }
        }
      }
    }
  }

  def pathDiff(interaction: ApiInteractionLike, spec: RfcState): PipelineItem[PathComponentId] = {
    val matchedPath = Utilities.resolvePath(interaction.request.url, spec.requestsState.pathComponents)

    if (matchedPath.isEmpty) {
      return PipelineItem(None, Iterator(UnmatchedUrl(interaction.asApiInteraction)))
    }
    PipelineItem(matchedPath, Iterator())
  }

  def requestDiff(interaction: ApiInteractionLike, spec: RfcState, pathId: PathComponentId): PipelineItem[HttpRequest] = {

    val matchedOperation = spec.requestsState.requests.values
      .find(r => r.requestDescriptor.pathComponentId == pathId && r.requestDescriptor.httpMethod == interaction.request.method)

    if (matchedOperation.isEmpty) {
      return PipelineItem(None, Iterator(UnmatchedHttpMethod(pathId, interaction.request.method, interaction.asApiInteraction)))
    }

    val request = matchedOperation.get
    if ((200 until 400) contains interaction.response.statusCode) {
      val requestDiff: Option[Iterator[RequestDiffResult]] = request.requestDescriptor.bodyDescriptor match {
        case d: UnsetBodyDescriptor => {
          if (interaction.request.bodyShape.isEmpty) {
            None
          } else {
            Some(Iterator(UnmatchedRequestBodyShape(request.requestId, interaction.request.contentType, ShapeDiffer.UnsetShape(interaction.request.bodyShape.asJs))))
          }
        }
        case d: ShapedBodyDescriptor => {
          if (d.httpContentType == interaction.request.contentType) {
            val shape = spec.shapesState.shapes(d.shapeId)
            val shapeDiff = ShapeDiffer.diff(shape, interaction.request.bodyShape)(spec.shapesState)
            if (shapeDiff.isEmpty) {
              None
            } else {
              Some(shapeDiff.map(d => UnmatchedRequestBodyShape(request.requestId, interaction.request.contentType, d)))
            }
          } else {
            Some(Iterator(UnmatchedRequestContentType(request.requestId, interaction.request.contentType, d.httpContentType)))
          }
        }
      }
      if (requestDiff.isDefined) {
        return PipelineItem(None, requestDiff.get)
      }
    }
    PipelineItem(matchedOperation, Iterator.empty)
  }

  def responseDiff(interaction: ApiInteractionLike, spec: RfcState, requestId: RequestId): PipelineItem[HttpResponse] = {

    // check for matching response status
    val matchedResponse = spec.requestsState.responses.values
      .find(r => r.responseDescriptor.requestId == requestId && r.responseDescriptor.httpStatusCode == interaction.response.statusCode)

    if (matchedResponse.isEmpty) {
      return PipelineItem(None, Iterator(UnmatchedHttpStatusCode(requestId, interaction.response.statusCode, interaction.asApiInteraction)))
    }

    val responseId = matchedResponse.get.responseId;
    val responseDiff: Option[Iterator[RequestDiffResult]] = matchedResponse.get.responseDescriptor.bodyDescriptor match {
      case d: UnsetBodyDescriptor => {
        if (interaction.response.bodyShape.isEmpty) {
          None
        } else {
          Some(
            Iterator(
              UnmatchedResponseBodyShape(
                responseId,
                interaction.response.contentType,
                interaction.response.statusCode,
                ShapeDiffer.UnsetShape(interaction.response.bodyShape.asJs)
              )
            )
          )
        }
      }
      case d: ShapedBodyDescriptor => {
        if (d.httpContentType == interaction.response.contentType) {
          val shape = spec.shapesState.shapes(d.shapeId)
          val shapeDiff = ShapeDiffer.diff(shape, interaction.response.bodyShape)(spec.shapesState)
          if (shapeDiff.isEmpty) {
            None
          } else {
            Some(shapeDiff.map(d => UnmatchedResponseBodyShape(responseId, interaction.response.contentType, interaction.response.statusCode, d)))
          }
        } else {
          Some(Iterator(UnmatchedResponseContentType(matchedResponse.get.responseId, interaction.response.contentType, d.httpContentType, interaction.response.statusCode)))
        }
      }
    }

    if (responseDiff.isDefined) {
      return PipelineItem(matchedResponse, responseDiff.get)
    }

    PipelineItem(matchedResponse, Iterator.empty)
  }
}


