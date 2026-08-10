package services.images

import java.io.File

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite

import scala.concurrent.Await
import scala.concurrent.duration._

class ImageServiceSpec extends PlaySpec with GuiceOneServerPerSuite {

  val tenSeconds = Duration(10, SECONDS)

  val imageService: ImageService = fakeApplication().injector.instanceOf[ImageService]

  "can determine the dimensions of an image" in {
    val landscapeImageFile = new File("test/resources/IMG_9758.JPG")

    val dimensions: (Int, Int) = Await.result(imageService.info(landscapeImageFile), tenSeconds)

    dimensions._1 must equal (3456)
    dimensions._2 must equal (2304)
  }

  "can crop an image" in {
    val landscapeImageFile = new File("test/resources/IMG_9758.JPG")
    val croppedFile = Await.result(imageService.cropImage(landscapeImageFile, 100, 100, 10, 10, "jpg"), tenSeconds).get
    val dimensions = Await.result(imageService.info(croppedFile), tenSeconds)
    dimensions._1 must equal (100)
    dimensions._2 must equal (100)
    croppedFile.delete()
  }

  "can resize to working size" in {
    val landscapeImageFile = new File("test/resources/IMG_9758.JPG")
    val resizedFile = Await.result(imageService.workingSize(landscapeImageFile)(scala.concurrent.ExecutionContext.Implicits.global), tenSeconds).get
    val dimensions = Await.result(imageService.info(resizedFile), tenSeconds)
    dimensions._1 must equal (800)
    dimensions._2 must be <= 800
    resizedFile.delete()
  }

  "can resize image with fill" in {
    val landscapeImageFile = new File("test/resources/IMG_9758.JPG")
    val resizedFile = Await.result(imageService.resizeImage(landscapeImageFile, Some(200), Some(100), 0, "jpg", true, None), tenSeconds).get
    val dimensions = Await.result(imageService.info(resizedFile), tenSeconds)
    dimensions._1 must equal (200)
    dimensions._2 must equal (100)
    resizedFile.delete()
  }

  "can resize image without fill" in {
    val landscapeImageFile = new File("test/resources/IMG_9758.JPG")
    val resizedFile = Await.result(imageService.resizeImage(landscapeImageFile, Some(200), Some(200), 0, "jpg", false, None), tenSeconds).get
    val dimensions = Await.result(imageService.info(resizedFile), tenSeconds)
    // Original is 3456x2304, aspect ratio 1.5. 200x200 box. Result should be 200x133.
    dimensions._1 must equal (200)
    dimensions._2 must equal (133)
    resizedFile.delete()
  }

  "can rotate and resize image" in {
    val landscapeImageFile = new File("test/resources/IMG_9758.JPG")
    val resizedFile = Await.result(imageService.resizeImage(landscapeImageFile, Some(200), Some(200), 90, "jpg", false, None), tenSeconds).get
    val dimensions = Await.result(imageService.info(resizedFile), tenSeconds)
    // Rotated 90 degrees: 2304x3456, aspect ratio 0.666. 200x200 box. Result should be 133x200.
    dimensions._1 must equal (133)
    dimensions._2 must equal (200)
    resizedFile.delete()
  }

}
