package model

case class Metadata (summary: Summary, formatSpecificAttributes: Option[FormatSpecificAttributes], metadata: Option[Map[String, String]], faces: Option[Seq[DetectedFace]])
