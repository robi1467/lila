package lila.game

import akka.stream.scaladsl._
import akka.util.ByteString
import play.api.libs.json._
import play.api.libs.ws.WSClient

import lila.common.config.BaseUrl

import chess.{ Replay, Situation, Game => ChessGame }
import chess.format.{ FEN, Forsyth, Uci }

final class GifExport(
    ws: WSClient,
    lightUserApi: lila.user.LightUserApi,
    baseUrl: BaseUrl,
    url: String
)(implicit ec: scala.concurrent.ExecutionContext) {
  def fromPov(pov: Pov, initialFen: Option[FEN]): Fu[Source[ByteString, _]] =
    lightUserApi preloadMany pov.game.userIds flatMap { _ =>
      ws.url(url)
        .withMethod("POST")
        .addHttpHeaders("Content-Type" -> "application/json")
        .withBody(
          Json.obj(
            "white"       -> Namer.playerTextBlocking(pov.game.whitePlayer, withRating = true)(lightUserApi.sync),
            "black"       -> Namer.playerTextBlocking(pov.game.blackPlayer, withRating = true)(lightUserApi.sync),
            "comment"     -> s"${baseUrl.value}/${pov.game.id} rendered with https://github.com/niklasf/lila-gif",
            "orientation" -> pov.color.name,
            "delay"       -> 70, // default delay for frames, centis
            "frames"      -> frames(pov.game, initialFen)
          )
        )
        .stream() flatMap {
        case res if res.status != 200 =>
          logger.warn(s"GifExport game ${pov.game.id} ${res.status}")
          fufail(res.statusText)
        case res => fuccess(res.bodyAsSource)
      }
    }

  private def frames(game: Game, initialFen: Option[FEN]) = {
    Replay.gameMoveWhileValid(
      game.pgnMoves,
      initialFen.map(_.value) | game.variant.initialFen,
      game.variant
    ) match {
      case (init, games, _) =>
        frame(init.situation, None, None) +: framesRec(games, Json.arr())
    }
  }

  @annotation.tailrec
  private def framesRec(games: List[(ChessGame, Uci.WithSan)], arr: JsArray): JsArray = games match {
    case Nil =>
      arr
    case (game, uci) :: tail =>
      // longer delay for last frame
      val delay = tail.isEmpty option 500
      framesRec(tail, arr :+ frame(game.situation, uci.uci.some, delay))
  }

  private def frame(situation: Situation, uci: Option[Uci], delay: Option[Int]) =
    Json
      .obj(
        "fen"      -> (Forsyth >> situation),
        "lastMove" -> uci.map(_.uci)
      )
      .add("check", situation.checkSquare.map(_.key))
      .add("delay", delay)
}