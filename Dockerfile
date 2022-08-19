FROM debian:buster
USER daemon
ENTRYPOINT ["./media-monkey"]
CMD []
USER root
RUN ["apt-get", "update"]
RUN ["apt-get", "install", "-y", "openjdk-11-jre-headless"]
RUN ["apt-get", "install", "-y", "imagemagick"]
RUN ["apt-get", "install", "-y", "ffmpeg"]
RUN ["apt-get", "install", "-y", "mediainfo"]
RUN ["apt-get", "install", "-y", "libimage-exiftool-perl"]
RUN ["apt-get", "install", "-y", "webp"]
