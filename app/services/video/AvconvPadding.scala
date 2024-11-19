package services.video

import play.api.Logger

trait AvconvPadding {

  val SixteenNine = (BigDecimal(16) / BigDecimal(9)).setScale(10, BigDecimal.RoundingMode.HALF_DOWN).toDouble

  def padding(sourceDimensions: Option[(Int, Int)], outputSize: Option[(Int, Int)], sourceAspectRatio: Option[Double], rotationToApply: Int): Option[String] = {
    outputSize.flatMap { os =>
      sourceDimensions.flatMap { sd =>

        val rotatedSourceDimensions = if (rotationToApply == 90 || rotationToApply == 270) {
          (sd._2, sd._1)
        } else {
          sd
        }

        val rotatedSourceAspectRatio = sourceAspectRatio.map { sar =>
          if (rotationToApply == 90 || rotationToApply == 270) {
            1 / sar
          } else {
            sar
          }
        }
        // TODO invert user supplied aspect ration on rotate

        val effectiveSourceAspectRatio = rotatedSourceAspectRatio.getOrElse((BigDecimal(rotatedSourceDimensions._1) / BigDecimal(rotatedSourceDimensions._2)).setScale(10, BigDecimal.RoundingMode.HALF_DOWN).toDouble)
        Logger.debug("Source dimensions " + rotatedSourceDimensions + " aspect ratio: " + effectiveSourceAspectRatio)

        val outputAspectRatio = (BigDecimal(os._1) / BigDecimal(os._2)).setScale(10, BigDecimal.RoundingMode.HALF_DOWN).toDouble
        Logger.debug("Ouptut dimensions " + os + " aspect ratio: " + outputAspectRatio)

        val d: Double = (effectiveSourceAspectRatio - outputAspectRatio).abs
        val aspectRatiosDiffer: Boolean = d > 0.05

        if (aspectRatiosDiffer) {

          if (effectiveSourceAspectRatio < outputAspectRatio) {
            Logger.debug("Applying padding")
            val paddedWidth = if (d < 0.05) rotatedSourceDimensions._1 else (BigDecimal(rotatedSourceDimensions._2) * outputAspectRatio).setScale(0, BigDecimal.RoundingMode.HALF_UP).rounded.toInt
            val x = BigDecimal(paddedWidth - rotatedSourceDimensions._1) / 2
            val paddingParameter = Some("pad=width=" + paddedWidth + ":height=" + rotatedSourceDimensions._2 + ":x=" + x.rounded.toInt)
            Logger.debug("Generated padding parameter: " + paddingParameter)
            paddingParameter

          } else {
            Logger.debug("Applying crop")
            val cropWidth = if (d < 0.05) rotatedSourceDimensions._1 else {
              val d1: BigDecimal = outputAspectRatio / effectiveSourceAspectRatio
              (BigDecimal(rotatedSourceDimensions._1) * d1).setScale(0, BigDecimal.RoundingMode.HALF_UP).rounded.toInt
            }
            //val x = BigDecimal(paddedWidth - os._1) / 2
            val croppingParameter = Some("crop=" + cropWidth + ":" + rotatedSourceDimensions._2)
            Logger.debug("Generated crop parameter: " + croppingParameter)
            croppingParameter
          }

        } else {
          Logger.debug("No padding required")
          None
        }
      }
    }
  }

}
