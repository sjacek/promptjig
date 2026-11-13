FROM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507

# Audit tooling only; no tool or OS package becomes part of a Promptjig distribution.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl gnupg python3 strace unzip \
    && rm -rf /var/lib/apt/lists/*
ENV HOME=/work/home GRADLE_USER_HOME=/work/home/.gradle MAVEN_USER_HOME=/work/home/.m2
WORKDIR /work
