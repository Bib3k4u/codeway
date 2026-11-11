# Sandbox image for learner code: JDK 21 (Java) plus Python 3, Node.js and g++ (C++20), on Ubuntu 24.04.
# Built on the server as codeway-sandbox:<tag>. When you change this file, bump SANDBOX_TAG in
# deploy/ec2/sandbox/tag and codeway.runner.image in application.properties together.
FROM eclipse-temurin:21-jdk-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 nodejs g++ libc6-dev \
 && rm -rf /var/lib/apt/lists/* \
 && python3 --version && node --version && g++ --version | head -1
