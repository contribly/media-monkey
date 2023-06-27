# The base image
FROM debian:buster

# Set the working directory
WORKDIR /app

# Updating the system and installing necessary software
RUN apt-get update && \
    apt-get install -y curl gnupg imagemagick ffmpeg mediainfo libimage-exiftool-perl webp && \
    apt-get install -y openjdk-11-jre-headless && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    sbt sbtVersion

# Run as root
USER root
