package services.mediainfo

import model.Track

trait MediainfoInterpreter {

  val Rotation: String = "Rotation"
  val Video: String = "Video"
  val Width: String = "Width"
  val Height: String = "Height"

  def videoCodec(mediainfoTracks: Option[Seq[Track]]): Option[String] = {
    mediainfoTracks.flatMap { mi =>
      mi.find(t => t.`type` == Video).flatMap { vt =>
        vt.fields.get("Codec_ID")
      }
    }
  }

  def videoDimensions(mediainfoTracks: Option[Seq[Track]]): Option[(Int, Int)] = {

    def parsePixels(i: String): Int = {
      i.stripSuffix(" pixels").replaceAll(" ", "").toInt
    }

    mediainfoTracks.flatMap{ mi =>
      mi.find(t => t.`type` == Video).flatMap{ vt =>
        vt.fields.get(Width).flatMap(w =>
          vt.fields.get(Height).map(h =>
            (parsePixels(w), parsePixels(h))
          )
        )
      }
    }
  }

  def inferRotation(mediainfoTracks: Option[Seq[Track]]): Int = {
    def parseRotation(r: String): Int = r.toDouble.toInt
    mediainfoTracks.flatMap(ts => ts.find(t => t.`type` == Video).flatMap(i => i.fields.get(Rotation))).fold(0)(mir => parseRotation(mir))
  }

}
