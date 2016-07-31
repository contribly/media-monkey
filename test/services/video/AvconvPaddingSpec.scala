package services.video

import org.specs2.mutable._

class AvconvPaddingSpec extends Specification with AvconvPadding {

  "can generate padding arguments from source and destination dimensions" in {
      val sourceDimensions = (854, 480)
      val targetDimensions = (296, 163)

      val paddingParameter: Option[String] = padding(Some(sourceDimensions), Some(targetDimensions), 0)

      paddingParameter must equalTo(Some("pad=854:480:0:0"))
  }

}
