## Media Monkey

Multi media file wrangling microservice.
Wraps various open source media tools with an HTTP/JSON interface.

### POST /meta

Format detect and extract meta data from an uploaded binary.
Backed by Apache Tika and mediainfo.

The media file should be sent as raw bytes on the request body.

```
curl -XPOST --data-binary "@test/resources/IMG_0004.MOV" http://localhost:9000/meta 
```

### POST /meta/tag

Apply meta data tags to an image file.

```
title
description
created
```

```
curl -F 'tags={"title": "A title", "description": "A description", created: "2013-01-08T14:58:06.000Z"}' -F "image=@test.jpg" localhost:9001/meta/tag > /tmp/tagged.jpg
```

### POST /detect-faces

Attempt to detect faces in the posted image.
This information might be used to make an informed decision about how to crop an image.
Backed by [openimaj](https://github.com/openimaj/openimaj).

```
callback
```

Bounds are reported as percentages of the images width and height.

### POST /crop

width, height, x, y

Crop an image

Scale the posted image and return it as a GIF, JPEG or PNG.
The desired return image format should be specified using the Accepts header on the request.
Backed by imagemagick.

### POST /scale

width, height, rotate, fill, gravity(Center / North)
callback
Accepts header

Scale the posted image and return it as a GIF, JPEG or PNG.
If a callback url as provided the service will return HTTP 202 Accepted and POST the processed media file back to your callback address at a later date.

The image file should be sent as raw bytes on the request body.
The desired return image format should be specified using the Accepts header on the request.
Backed by imagemagick.

### POST /video/transcode

Transcode a video.

```
curl -XPOST -H "Accept: video/mp4" --data-binary "@test/resources/IMG_0004.MOV" 'http://localhost:9000/video/transcode?width=800&height=600&rotate=0' --output /tmp/out.mp4
```


## Build

```
sbt clean docker:publishLocal
```


## Docker

This microservice is available as a Docker image:

```
docker run -p 9001:9001 eelpie/media-monkey
```

