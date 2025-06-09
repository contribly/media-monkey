package services.geo

import model.LatLong

trait ExifLocationExtractor {

  def extractLocationFrom(metadata: Map[String, String]): Option[LatLong] = {
    metadata.get("geo:lat").flatMap(tryParse).flatMap { lat =>
      metadata.get("geo:long").flatMap(tryParse).flatMap { long =>
        if (lat != 0 || long != 0) {
          Some(LatLong(lat, long))
        } else {
          None
        }
      }
    }
  }

  private def tryParse(str: String): Option[Double] = try {
    Some(str.toDouble)
  } catch {
    case _ => None
  }

}
